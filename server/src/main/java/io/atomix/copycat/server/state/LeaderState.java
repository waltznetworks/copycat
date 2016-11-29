/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server.state;

import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.concurrent.ComposableFuture;
import io.atomix.catalyst.concurrent.Scheduled;
import io.atomix.copycat.Command;
import io.atomix.copycat.Query;
import io.atomix.copycat.error.CopycatError;
import io.atomix.copycat.error.CopycatException;
import io.atomix.copycat.protocol.*;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.protocol.*;
import io.atomix.copycat.server.storage.entry.*;
import io.atomix.copycat.server.storage.system.Configuration;
import io.atomix.copycat.session.Session;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Leader state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
final class LeaderState extends ActiveState {
  private final LeaderAppender appender;
  private Scheduled appendTimer;
  private long configuring;

  public LeaderState(ServerContext context) {
    super(context);
    this.appender = new LeaderAppender(this);
  }

  @Override
  public CopycatServer.State type() {
    return CopycatServer.State.LEADER;
  }

  @Override
  public synchronized CompletableFuture<AbstractState> open() {
    // Reset state for the leader.
    takeLeadership();

    // Append initial entries to the log, including an initial no-op entry and the server's configuration.
    appendInitialEntries();

    // Commit the initial leader entries.
    commitInitialEntries();

    return super.open()
      .thenRun(this::startAppendTimer)
      .thenApply(v -> this);
  }

  /**
   * Sets the current node as the cluster leader.
   */
  private void takeLeadership() {
    context.setLeader(context.getCluster().member().id());
    context.getClusterState().getRemoteMemberStates().forEach(m -> m.resetState(context.getLog()));
  }

  /**
   * Appends initial entries to the log to take leadership.
   */
  private void appendInitialEntries() {
    final long term = context.getTerm();

    // Append a no-op entry to reset session timeouts and commit entries from prior terms.
    try (InitializeEntry entry = context.getLog().create(InitializeEntry.class)) {
      entry.setTerm(term)
        .setTimestamp(appender.time());
      assert context.getLog().append(entry) == appender.index();
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    }

    // Append a configuration entry to propagate the leader's cluster configuration.
    configure(context.getCluster().members());
  }

  /**
   * Commits a no-op entry to the log, ensuring any entries from a previous term are committed.
   */
  private CompletableFuture<Void> commitInitialEntries() {
    // The Raft protocol dictates that leaders cannot commit entries from previous terms until
    // at least one entry from their current term has been stored on a majority of servers. Thus,
    // we force entries to be appended up to the leader's no-op entry. The LeaderAppender will ensure
    // that the commitIndex is not increased until the no-op entry (appender.index()) is committed.
    CompletableFuture<Void> future = new CompletableFuture<>();
    appender.appendEntries(appender.index()).whenComplete((resultIndex, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          context.getStateMachine().apply(resultIndex);
          future.complete(null);
        } else {
          context.setLeader(0);
          context.transition(CopycatServer.State.FOLLOWER);
        }
      }
    });
    return future;
  }

  /**
   * Starts sending AppendEntries requests to all cluster members.
   */
  private void startAppendTimer() {
    // Set a timer that will be used to periodically synchronize with other nodes
    // in the cluster. This timer acts as a heartbeat to ensure this node remains
    // the leader.
    LOGGER.debug("{} - Starting append timer", context.getCluster().member().address());
    appendTimer = context.getThreadContext().schedule(Duration.ZERO, context.getHeartbeatInterval(), this::appendMembers);
  }

  /**
   * Sends AppendEntries requests to members of the cluster that haven't heard from the leader in a while.
   */
  private void appendMembers() {
    context.checkThread();
    if (isOpen()) {
      appender.appendEntries();
    }
  }

  /**
   * Checks to determine whether any sessions have expired.
   * <p>
   * Copycat allows only leaders to explicitly unregister sessions due to expiration. This ensures
   * that sessions cannot be expired by lengthy election periods or other disruptions to time.
   * To do so, the leader periodically iterates through registered sessions and checks for sessions
   * that have been marked suspicious. The internal state machine marks sessions as suspicious when
   * keep alive entries are not committed for longer than the session timeout. Once the leader marks
   * a session as suspicious, it will log and replicate an {@link UnregisterEntry} to unregister the session.
   */
  private void checkSessions() {
    long term = context.getTerm();

    // Iterate through all currently registered sessions.
    for (ServerSessionContext session : context.getStateMachine().executor().context().sessions().sessions.values()) {
      // If the session isn't already being unregistered by this leader and a keep-alive entry hasn't
      // been committed for the session in some time, log and commit a new UnregisterEntry.
      if (session.state() == Session.State.UNSTABLE && !session.isUnregistering()) {
        LOGGER.debug("{} - Detected expired session: {}", context.getCluster().member().address(), session.id());

        // Log the unregister entry, indicating that the session was explicitly unregistered by the leader.
        // This will result in state machine expire() methods being called when the entry is applied.
        final long index;
        try (UnregisterEntry entry = context.getLog().create(UnregisterEntry.class)) {
          entry.setTerm(term)
            .setSession(session.id())
            .setExpired(true)
            .setTimestamp(System.currentTimeMillis());
          index = context.getLog().append(entry);
          LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
        }

        // Commit the unregister entry and apply it to the state machine.
        appender.appendEntries(index).whenComplete((result, error) -> {
          if (isOpen()) {
            context.getStateMachine().apply(index);
          }
        });

        // Mark the session as being unregistered in order to ensure this leader doesn't attempt
        // to unregister it again.
        session.unregister();
      }
    }
  }

  /**
   * Returns a boolean value indicating whether a configuration is currently being committed.
   *
   * @return Indicates whether a configuration is currently being committed.
   */
  boolean configuring() {
    return configuring > 0;
  }

  /**
   * Returns a boolean value indicating whether the leader is still being initialized.
   *
   * @return Indicates whether the leader is still being initialized.
   */
  boolean initializing() {
    // If the leader index is 0 or is greater than the commitIndex, do not allow configuration changes.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    return appender.index() == 0 || context.getCommitIndex() < appender.index();
  }

  /**
   * Commits the given configuration.
   */
  protected CompletableFuture<Long> configure(Collection<Member> members) {
    final long index;
    try (ConfigurationEntry entry = context.getLog().create(ConfigurationEntry.class)) {
      entry.setTerm(context.getTerm())
        .setTimestamp(System.currentTimeMillis())
        .setMembers(members);
      index = context.getLog().append(entry);
      LOGGER.debug("{} - Appended {} to log at index {}", context.getCluster().member().address(), entry, index);

      // Store the index of the configuration entry in order to prevent other configurations from
      // being logged and committed concurrently. This is an important safety property of Raft.
      configuring = index;
      context.getClusterState().configure(new Configuration(entry.getIndex(), entry.getTerm(), entry.getTimestamp(), entry.getMembers()));
    }

    return appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        // Reset the configuration index to allow new configuration changes to be committed.
        configuring = 0;
      }
    });
  }

  @Override
  public CompletableFuture<JoinResponse> join(final JoinRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (configuring() || initializing()) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the member is already a known member of the cluster, complete the join successfully.
    if (context.getCluster().member(request.member().id()) != null) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.OK)
        .withIndex(context.getClusterState().getConfiguration().index())
        .withTerm(context.getClusterState().getConfiguration().term())
        .withTime(context.getClusterState().getConfiguration().time())
        .withMembers(context.getCluster().members())
        .build()));
    }

    Member member = request.member();

    // Add the joining member to the members list. If the joining member's type is ACTIVE, join the member in the
    // PROMOTABLE state to allow it to get caught up without impacting the quorum size.
    Collection<Member> members = context.getCluster().members();
    members.add(new ServerMember(member.type(), member.serverAddress(), member.clientAddress(), Instant.now()));

    CompletableFuture<JoinResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(JoinResponse.builder()
            .withStatus(Response.Status.OK)
            .withIndex(index)
            .withTerm(context.getClusterState().getConfiguration().term())
            .withTime(context.getClusterState().getConfiguration().time())
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(JoinResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<ReconfigureResponse> reconfigure(final ReconfigureRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    // If the leader index is 0 or is greater than the commitIndex, reject the promote requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (configuring() || initializing()) {
      return CompletableFuture.completedFuture(logResponse(ReconfigureResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the member is not a known member of the cluster, fail the promotion.
    ServerMember existingMember = context.getClusterState().member(request.member().id());
    if (existingMember == null) {
      return CompletableFuture.completedFuture(logResponse(ReconfigureResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    // If the configuration request index is less than the last known configuration index for
    // the leader, fail the request to ensure servers can't reconfigure an old configuration.
    if (request.index() > 0 && request.index() < context.getClusterState().getConfiguration().index() || request.term() != context.getClusterState().getConfiguration().term()
      && (existingMember.type() != request.member().type() || existingMember.status() != request.member().status())) {
      return CompletableFuture.completedFuture(logResponse(ReconfigureResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.CONFIGURATION_ERROR)
        .build()));
    }

    Member member = request.member();

    // If the client address is being set or has changed, update the configuration.
    if (member.clientAddress() != null && (existingMember.clientAddress() == null || !existingMember.clientAddress().equals(member.clientAddress()))) {
      existingMember.update(member.clientAddress(), Instant.now());
    }

    // Update the member type.
    existingMember.update(request.member().type(), Instant.now());

    Collection<Member> members = context.getCluster().members();

    CompletableFuture<ReconfigureResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(ReconfigureResponse.builder()
            .withStatus(Response.Status.OK)
            .withIndex(index)
            .withTerm(context.getClusterState().getConfiguration().term())
            .withTime(context.getClusterState().getConfiguration().time())
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(ReconfigureResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<LeaveResponse> leave(final LeaveRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (configuring() || initializing()) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leaving member is not a known member of the cluster, complete the leave successfully.
    if (context.getCluster().member(request.member().id()) == null) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.OK)
        .withMembers(context.getCluster().members())
        .build()));
    }

    Member member = request.member();

    Collection<Member> members = context.getCluster().members();
    members.remove(member);

    CompletableFuture<LeaveResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(LeaveResponse.builder()
            .withStatus(Response.Status.OK)
            .withIndex(index)
            .withTerm(context.getClusterState().getConfiguration().term())
            .withTime(context.getClusterState().getConfiguration().time())
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(LeaveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<PollResponse> poll(final PollRequest request) {
    logRequest(request);
    return CompletableFuture.completedFuture(logResponse(PollResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(context.getTerm())
      .withAccepted(false)
      .build()));
  }

  @Override
  public CompletableFuture<VoteResponse> vote(final VoteRequest request) {
    if (updateTermAndLeader(request.term(), 0)) {
      LOGGER.debug("{} - Received greater term", context.getCluster().member().address());
      context.transition(CopycatServer.State.FOLLOWER);
      return super.vote(request);
    } else {
      logRequest(request);
      return CompletableFuture.completedFuture(logResponse(VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withVoted(false)
        .build()));
    }
  }

  @Override
  public CompletableFuture<AppendResponse> append(final AppendRequest request) {
    context.checkThread();
    if (updateTermAndLeader(request.term(), request.leader())) {
      CompletableFuture<AppendResponse> future = super.append(request);
      context.transition(CopycatServer.State.FOLLOWER);
      return future;
    } else if (request.term() < context.getTerm()) {
      logRequest(request);
      return CompletableFuture.completedFuture(logResponse(AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().lastIndex())
        .build()));
    } else {
      context.setLeader(request.leader()).transition(CopycatServer.State.FOLLOWER);
      return super.append(request);
    }
  }

  @Override
  protected CompletableFuture<CommandResponse> command(final CommandRequest request) {
    context.checkThread();
    logRequest(request);

    // Get the client's server session. If the session doesn't exist, return an unknown session error.
    ServerSessionContext session = context.getStateMachine().executor().context().sessions().getSession(request.session());
    if (session == null) {
      return CompletableFuture.completedFuture(logResponse(CommandResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    ComposableFuture<CommandResponse> future = new ComposableFuture<>();
    sequenceCommand(request, session, future);
    return future;
  }

  /**
   * Sequences the given command to the log.
   */
  private void sequenceCommand(CommandRequest request, ServerSessionContext session, CompletableFuture<CommandResponse> future) {
    // If the command is LINEARIZABLE and the session's current sequence number is less then one prior to the request
    // sequence number, queue this request for handling later. We want to handle command requests in the order in which
    // they were sent by the client. Note that it's possible for the session sequence number to be greater than the request
    // sequence number. In that case, it's likely that the command was submitted more than once to the
    // cluster, and the command will be deduplicated once applied to the state machine.
    if (request.sequence() > session.nextRequestSequence()) {
      session.registerRequest(request.sequence(), () -> applyCommand(request, session, future));
    } else {
      applyCommand(request, session, future);
    }
  }

  /**
   * Applies the given command to the log.
   */
  private void applyCommand(CommandRequest request, ServerSessionContext session, CompletableFuture<CommandResponse> future) {
    final Command command = request.command();

    final long term = context.getTerm();
    final long timestamp = System.currentTimeMillis();
    final long index;

    // Create a CommandEntry and append it to the log.
    try (CommandEntry entry = context.getLog().create(CommandEntry.class)) {
      entry.setTerm(term)
        .setSession(request.session())
        .setTimestamp(timestamp)
        .setSequence(request.sequence())
        .setCommand(command);
      index = context.getLog().append(entry);
      LOGGER.debug("{} - Appended {} to log at index {}", context.getCluster().member().address(), entry, index);
    }

    // Replicate the command to followers.
    appendCommand(index, future);

    // Set the last processed request for the session. This will cause sequential command callbacks to be executed.
    session.setRequestSequence(request.sequence());
  }

  /**
   * Sends append requests for a command to followers.
   */
  private void appendCommand(long index, CompletableFuture<CommandResponse> future) {
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          applyCommand(index, future);
        } else {
          future.complete(logResponse(CommandResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
  }

  /**
   * Applies a command to the state machine.
   */
  private void applyCommand(long index, CompletableFuture<CommandResponse> future) {
    context.getStateMachine().<ServerStateMachine.Result>apply(index).whenComplete((result, error) -> {
      if (isOpen()) {
        completeOperation(result, CommandResponse.builder(), error, future);
      }
    });
  }

  @Override
  protected CompletableFuture<QueryResponse> query(final QueryRequest request) {
    Query query = request.query();

    final long timestamp = System.currentTimeMillis();

    context.checkThread();
    logRequest(request);

    QueryEntry entry = context.getLog().create(QueryEntry.class)
      .setIndex(request.index())
      .setTerm(context.getTerm())
      .setTimestamp(timestamp)
      .setSession(request.session())
      .setSequence(request.sequence())
      .setQuery(query);

    Query.ConsistencyLevel consistency = query.consistency();
    if (consistency == null)
      return queryLinearizable(entry);

    switch (consistency) {
      case SEQUENTIAL:
        return queryLocal(entry);
      case LINEARIZABLE_LEASE:
        return queryBoundedLinearizable(entry);
      case LINEARIZABLE:
        return queryLinearizable(entry);
      default:
        throw new IllegalStateException("unknown consistency level");
    }
  }

  /**
   * Submits a query with lease bounded linearizable consistency.
   */
  private CompletableFuture<QueryResponse> queryBoundedLinearizable(QueryEntry entry) {
    // Get the client's server session. If the session doesn't exist, return an unknown session error.
    ServerSessionContext session = context.getStateMachine().executor().context().sessions().getSession(entry.getSession());
    if (session == null) {
      return CompletableFuture.completedFuture(logResponse(QueryResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    CompletableFuture<QueryResponse> future = new CompletableFuture<>();
    sequenceBoundedLinearizableQuery(entry, session, future);
    return future;
  }

  /**
   * Sequences a bounded linearizable query.
   */
  private void sequenceBoundedLinearizableQuery(QueryEntry entry, ServerSessionContext session, CompletableFuture<QueryResponse> future) {
    // If the query's sequence number is greater than the session's current sequence number, queue the request for
    // handling once the state machine is caught up.
    if (entry.getSequence() > session.getCommandSequence()) {
      session.registerSequenceQuery(entry.getSequence(), () -> applyQuery(entry, future));
    } else {
      applyQuery(entry, future);
    }
  }

  /**
   * Submits a query with strict linearizable consistency.
   */
  private CompletableFuture<QueryResponse> queryLinearizable(QueryEntry entry) {
    // Get the client's server session. If the session doesn't exist, return an unknown session error.
    ServerSessionContext session = context.getStateMachine().executor().context().sessions().getSession(entry.getSession());
    if (session == null) {
      return CompletableFuture.completedFuture(logResponse(QueryResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    CompletableFuture<QueryResponse> future = new CompletableFuture<>();
    appendLinearizableQuery(entry, session, future);
    return future;
  }

  /**
   * Sends an append request for the given query entry.
   */
  private void appendLinearizableQuery(QueryEntry entry, ServerSessionContext session, CompletableFuture<QueryResponse> future) {
    appender.appendEntries().whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          entry.acquire();
          sequenceLinearizableQuery(entry, future);
        } else {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.QUERY_ERROR)
            .build()));
        }
      }
      entry.release();
    });
  }

  /**
   * Sequences a linearizable query.
   */
  private void sequenceLinearizableQuery(QueryEntry entry, CompletableFuture<QueryResponse> future) {
    // Get the client's server session. If the session doesn't exist, return an unknown session error.
    ServerSessionContext session = context.getStateMachine().executor().context().sessions().getSession(entry.getSession());
    if (session == null) {
      future.complete(logResponse(QueryResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    } else {
      // If the query's sequence number is greater than the session's current sequence number, queue the request for
      // handling once the state machine is caught up.
      if (entry.getSequence() > session.getCommandSequence()) {
        LOGGER.debug("{} - Registering query entry: {}", context.getCluster().member().address(), entry);
        session.registerSequenceQuery(entry.getSequence(), () -> applyQuery(entry, future));
      } else {
        LOGGER.debug("{} - Applying query immediately: {}", context.getCluster().member().address(), entry);
        applyQuery(entry, future);
      }
    }
  }

  @Override
  protected CompletableFuture<RegisterResponse> register(RegisterRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;
    final long timeout = context.getSessionTimeout().toMillis();

    context.checkThread();
    logRequest(request);

    try (RegisterEntry entry = context.getLog().create(RegisterEntry.class)) {
      entry.setTerm(context.getTerm())
        .setTimestamp(timestamp)
        .setClient(request.client())
        .setTimeout(timeout);
      index = context.getLog().append(entry);
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    }

    CompletableFuture<RegisterResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          context.getStateMachine().apply(index).whenComplete((sessionId, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withSession((Long) sessionId)
                  .withTimeout(timeout)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withMembers(context.getCluster().members().stream()
                    .map(Member::clientAddress)
                    .filter(m -> m != null)
                    .collect(Collectors.toList())).build()));
              } else if (sessionError instanceof CompletionException && sessionError.getCause() instanceof CopycatException) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) sessionError.getCause()).getType())
                  .build()));
              } else if (sessionError instanceof CopycatException) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(CopycatError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
          });
        } else {
          future.complete(logResponse(RegisterResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  @Override
  protected CompletableFuture<ConnectResponse> connect(ConnectRequest request, Connection connection) {
    context.checkThread();
    logRequest(request);

    context.getStateMachine().executor().context().sessions().registerConnection(request.client(), connection);

    AcceptRequest acceptRequest = AcceptRequest.builder()
      .withClient(request.client())
      .withAddress(context.getCluster().member().serverAddress())
      .build();
    return accept(acceptRequest)
      .thenApply(acceptResponse -> ConnectResponse.builder()
        .withStatus(Response.Status.OK)
        .withLeader(context.getCluster().member().clientAddress())
        .withMembers(context.getCluster().members().stream()
          .map(Member::clientAddress)
          .filter(m -> m != null)
          .collect(Collectors.toList()))
        .build())
      .thenApply(this::logResponse);
  }

  @Override
  protected CompletableFuture<AcceptResponse> accept(AcceptRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    context.checkThread();
    logRequest(request);

    try (ConnectEntry entry = context.getLog().create(ConnectEntry.class)) {
      entry.setTerm(context.getTerm())
        .setClient(request.client())
        .setTimestamp(timestamp)
        .setAddress(request.address());
      index = context.getLog().append(entry);
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    }

    LOGGER.debug("{} - AcceptRequest register address: {} {}", context.getCluster().member().address(), request.client(), request.address());
    context.getStateMachine().executor().context().sessions().registerAddress(request.client(), request.address());

    CompletableFuture<AcceptResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          context.getStateMachine().apply(index).whenComplete((connectResult, connectError) -> {
            if (isOpen()) {
              if (connectError == null) {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.OK)
                  .build()));
              } else if (connectError instanceof CompletionException && connectError.getCause() instanceof CopycatException) {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) connectError.getCause()).getType())
                  .build()));
              } else if (connectError instanceof CopycatException) {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) connectError).getType())
                  .build()));
              } else {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(CopycatError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
          });
        } else {
          future.complete(logResponse(AcceptResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  @Override
  protected CompletableFuture<KeepAliveResponse> keepAlive(KeepAliveRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    context.checkThread();
    logRequest(request);

    try (KeepAliveEntry entry = context.getLog().create(KeepAliveEntry.class)) {
      entry.setTerm(context.getTerm())
        .setSession(request.session())
        .setCommandSequence(request.commandSequence())
        .setEventIndex(request.eventIndex())
        .setTimestamp(timestamp);
      index = context.getLog().append(entry);
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    }

    CompletableFuture<KeepAliveResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          context.getStateMachine().apply(index).whenComplete((sessionResult, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withMembers(context.getCluster().members().stream()
                    .map(Member::clientAddress)
                    .filter(m -> m != null)
                    .collect(Collectors.toList())).build()));
              } else if (sessionError instanceof CompletionException && sessionError.getCause() instanceof CopycatException) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withError(((CopycatException) sessionError.getCause()).getType())
                  .build()));
              } else if (sessionError instanceof CopycatException) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withError(((CopycatException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withError(CopycatError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
          });
        } else {
          future.complete(logResponse(KeepAliveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withLeader(context.getCluster().member().clientAddress())
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  @Override
  protected CompletableFuture<UnregisterResponse> unregister(UnregisterRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    context.checkThread();
    logRequest(request);

    try (UnregisterEntry entry = context.getLog().create(UnregisterEntry.class)) {
      entry.setTerm(context.getTerm())
        .setSession(request.session())
        .setExpired(false)
        .setTimestamp(timestamp);
      index = context.getLog().append(entry);
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    }

    CompletableFuture<UnregisterResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          context.getStateMachine().apply(index).whenComplete((unregisterResult, unregisterError) -> {
            if (isOpen()) {
              if (unregisterError == null) {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.OK)
                  .build()));
              } else if (unregisterError instanceof CompletionException && unregisterError.getCause() instanceof CopycatException) {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) unregisterError.getCause()).getType())
                  .build()));
              } else if (unregisterError instanceof CopycatException) {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) unregisterError).getType())
                  .build()));
              } else {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(CopycatError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
          });
        } else {
          future.complete(logResponse(UnregisterResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  /**
   * Cancels the append timer.
   */
  private void cancelAppendTimer() {
    if (appendTimer != null) {
      LOGGER.debug("{} - Cancelling append timer", context.getCluster().member().address());
      appendTimer.cancel();
    }
  }

  /**
   * Ensures the local server is not the leader.
   */
  private void stepDown() {
    if (context.getLeader() != null && context.getLeader().equals(context.getCluster().member())) {
      context.setLeader(0);
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close()
      .thenRun(appender::close)
      .thenRun(this::cancelAppendTimer)
      .thenRun(this::stepDown);
  }

}
