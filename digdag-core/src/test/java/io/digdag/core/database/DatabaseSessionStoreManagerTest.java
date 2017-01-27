package io.digdag.core.database;

import java.util.*;
import java.time.Instant;
import java.time.ZoneId;

import org.hamcrest.Matchers;
import org.junit.*;
import com.google.common.base.Optional;
import com.google.common.collect.*;
import io.digdag.core.repository.*;
import io.digdag.core.schedule.*;
import io.digdag.core.session.*;
import io.digdag.core.workflow.*;
import io.digdag.spi.ScheduleTime;
import io.digdag.client.config.ConfigFactory;
import static io.digdag.core.database.DatabaseTestingUtils.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

public class DatabaseSessionStoreManagerTest
{
    private DatabaseFactory factory;
    private ProjectStoreManager projectStoreManager;
    private ProjectStore projectStore;

    private SessionStoreManager manager;
    private SessionStore store;

    private ConfigFactory cf = createConfigFactory();
    private WorkflowExecutor exec;
    private AttemptBuilder attemptBuilder;

    private StoredProject proj;
    private StoredRevision rev;
    private StoredWorkflowDefinition wf1;
    private StoredWorkflowDefinition wf2;

    private StoredProject otherProj;
    private StoredRevision otherProjRev;
    private StoredWorkflowDefinition otherProjWf1;
    private StoredWorkflowDefinition otherProjWf2;
    private StoredSessionAttemptWithSession otherProjAttempt1;
    private StoredSessionWithLastAttempt otherProjSession1;

    @Before
    public void setUp()
        throws Exception
    {
        factory = setupDatabase();
        factory.begin(() -> {
            projectStoreManager = factory.getProjectStoreManager();
            projectStore = projectStoreManager.getProjectStore(0);
            manager = factory.getSessionStoreManager();
            store = manager.getSessionStore(0);

            exec = factory.getWorkflowExecutor();

            Project srcProj = Project.of("repo1");
            Revision srcRev = createRevision("rev1");
            WorkflowDefinition srcWf1 = createWorkflow("wf1");
            WorkflowDefinition srcWf2 = createWorkflow("wf2");

            attemptBuilder = new AttemptBuilder(
                    new SchedulerManager(ImmutableSet.of()),
                    new SlaCalculator());

            proj = projectStore.putAndLockProject(
                    srcProj,
                    (store, stored) -> {
                        ProjectControl lock = new ProjectControl(store, stored);

                        rev = lock.insertRevision(srcRev);

                        List<StoredWorkflowDefinition> storedWfs = lock.insertWorkflowDefinitionsWithoutSchedules(rev, ImmutableList.of(srcWf1, srcWf2));
                        wf1 = storedWfs.get(0);
                        wf2 = storedWfs.get(1);
                        return lock.get();
                    });

            Project otherSrcProj = Project.of("otherRepo1");
            Revision otherSrcRev = createRevision("otherRev1");
            WorkflowDefinition otherSrcWf1 = createWorkflow("otherProjWf1");
            WorkflowDefinition otherSrcWf2 = createWorkflow("otherProjWf2");

            otherProj = projectStore.putAndLockProject(
                    otherSrcProj,
                    (store, stored) -> {
                        ProjectControl lock = new ProjectControl(store, stored);

                        otherProjRev = lock.insertRevision(otherSrcRev);

                        List<StoredWorkflowDefinition> storedWfs = lock.insertWorkflowDefinitionsWithoutSchedules(otherProjRev, ImmutableList.of(otherSrcWf1, otherSrcWf2));
                        otherProjWf1 = storedWfs.get(0);
                        otherProjWf2 = storedWfs.get(1);
                        return lock.get();
                    });

            AttemptRequest otherProjAr1 = attemptBuilder.buildFromStoredWorkflow(
                    otherProjRev,
                    otherProjWf1,
                    cf.create(),
                    ScheduleTime.runNow(Instant.ofEpochSecond(Instant.now().getEpochSecond())));
            otherProjAttempt1 = exec.submitWorkflow(0, otherProjAr1, otherSrcWf1);
            otherProjSession1 = store.getSessionById(otherProjAttempt1.getSessionId());
            return null;
        });
    }

    @After
    public void destroy()
    {
        factory.close();
    }

    @Test
    public void testConflicts()
        throws Exception
    {
        factory.begin(() -> {
            Instant sessionTime1 = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 3600 * 3600);

            AttemptRequest ar1 = attemptBuilder.buildFromStoredWorkflow(
                    rev,
                    wf1,
                    cf.create(),
                    ScheduleTime.runNow(sessionTime1));

            exec.submitWorkflow(0, ar1, wf1);

            // same session conflicts
            assertConflict(() -> {
                propagateOnly(ResourceConflictException.class, () ->
                        exec.submitWorkflow(0, ar1, wf1)
                );
            });

            // different session params conflicts
            assertConflict(() -> {
                propagateOnly(ResourceConflictException.class, () ->
                        exec.submitWorkflow(0,
                                ImmutableAttemptRequest.builder().from(ar1)
                                        .sessionParams(cf.create().set("a", 1))
                                        .build(),
                                wf1)
                );
            });

            // different timezone conflicts
            assertConflict(() -> {
                propagateOnly(ResourceConflictException.class, () ->
                        exec.submitWorkflow(0,
                                ImmutableAttemptRequest.builder().from(ar1)
                                        .timeZone(ZoneId.of("-0300"))
                                        .build(),
                                wf1)
                );
            });

            // different retry attempt name doesn't conflict
            assertNotConflict(() -> {
                propagateOnly(ResourceConflictException.class, () ->
                        exec.submitWorkflow(0,
                                ImmutableAttemptRequest.builder().from(ar1)
                                        .retryAttemptName(Optional.of("retry1"))
                                        .build(),
                                wf1)
                );
            });

            // different session time doesn't conflict
            assertNotConflict(() -> {
                propagateOnly(ResourceConflictException.class, () ->
                        exec.submitWorkflow(0,
                                ImmutableAttemptRequest.builder().from(ar1)
                                        .sessionTime(sessionTime1.plusSeconds(1))
                                        .build(),
                                wf1)
                );
            });

            // different workflow name doesn't conflict
            assertNotConflict(() -> {
                propagateOnly(ResourceConflictException.class, () ->
                        exec.submitWorkflow(0,
                                ImmutableAttemptRequest.builder().from(ar1)
                                        .workflowName("another")
                                        .build(),
                                wf1)
                );
            });

            // wrong siteId causes NotFound
            assertNotFound(() -> {
                propagateOnly(ResourceNotFoundException.class, () ->
                        exec.submitWorkflow(1, ar1, wf1)
                );
            });

            // wrong project id causes NotFound
            assertNotFound(() -> {
                propagateOnly(ResourceNotFoundException.class, () ->
                        exec.submitWorkflow(0,
                                ImmutableAttemptRequest.builder().from(ar1)
                                        .stored(AttemptRequest.Stored.of(ImmutableStoredRevision.builder().from(rev).projectId(30).build(), wf1))
                                        .retryAttemptName(Optional.of("retryWithWrongProjectId"))
                                        .build(),
                                wf1)
                );
            });

            // wrong workflow id causes NotFound
            assertNotFound(() -> {
                propagateOnly(ResourceNotFoundException.class, () ->
                        exec.submitWorkflow(0,
                                ImmutableAttemptRequest.builder().from(ar1)
                                        .stored(AttemptRequest.Stored.of(rev, ImmutableStoredWorkflowDefinition.builder().from(wf1).id(30).build()))
                                        .retryAttemptName(Optional.of("retryWithWrongWfId"))
                                        .build(),
                                wf1)
                );
            });
            return null;
        });
    }

    @Test
    public void testGetAndNotFounds()
        throws Exception
    {
        factory.begin(() -> {
            Instant sessionTime1 = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 3600 * 3600);
            Instant sessionTime2 = sessionTime1.plusSeconds(3600);

            WorkflowDefinition def1 = WorkflowDefinition.of(
                    wf1.getName(),
                    cf.create()
                            .setNested("+step1", cf.create().set("sh>", "echo step1"))
                            .setNested("+step2", cf.create().set("sh>", "echo step2")),
                    ZoneId.of("UTC")
            );

            // session
            AttemptRequest ar1 = attemptBuilder.buildFromStoredWorkflow(
                    rev,
                    wf1,
                    cf.create(),
                    ScheduleTime.runNow(sessionTime1));
            StoredSessionAttemptWithSession attempt1 = exec.submitWorkflow(0, ar1, def1);
            StoredSessionWithLastAttempt session1 = store.getSessionById(attempt1.getSessionId());

            assertSessionAndLastAttemptEquals(session1, attempt1);
            assertThat(ImmutableList.of(session1, otherProjSession1), is(store.getSessions(100, Optional.absent())));
            assertThat(ImmutableList.of(session1), is(store.getSessionsOfProject(proj.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(session1), is(store.getSessionsOfWorkflowByName(proj.getId(), wf1.getName(), 100, Optional.absent())));
            assertEmpty(store.getSessionsOfWorkflowByName(proj.getId(), wf2.getName(), 100, Optional.absent()));
            assertThat(store.getActiveAttemptsOfWorkflow(wf1.getId(), 100, Optional.absent()), containsInAnyOrder(attempt1));
            assertThat(store.getActiveAttemptsOfWorkflow(wf2.getId(), 100, Optional.absent()), is(Matchers.empty()));

            // session + different session time
            AttemptRequest ar2 = attemptBuilder.buildFromStoredWorkflow(
                    rev,
                    wf1,
                    cf.create(),
                    ScheduleTime.runNow(sessionTime2));
            StoredSessionAttemptWithSession attempt2 = exec.submitWorkflow(0, ar2, def1);
            StoredSessionWithLastAttempt session2 = store.getSessionById(attempt2.getSessionId());

            assertSessionAndLastAttemptEquals(session2, attempt2);
            assertThat(ImmutableList.of(session2, session1, otherProjSession1), is(store.getSessions(100, Optional.absent())));
            assertThat(ImmutableList.of(session2, session1), is(store.getSessionsOfProject(proj.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(session2, session1), is(store.getSessionsOfWorkflowByName(proj.getId(), wf1.getName(), 100, Optional.absent())));
            assertEmpty(store.getSessionsOfWorkflowByName(proj.getId(), wf2.getName(), 100, Optional.absent()));
            assertThat(store.getActiveAttemptsOfWorkflow(wf1.getId(), 100, Optional.absent()), containsInAnyOrder(attempt1, attempt2));
            assertThat(store.getActiveAttemptsOfWorkflow(wf2.getId(), 100, Optional.absent()), is(Matchers.empty()));

            // session + different retry attempt name
            String retryAttemptName = "attempt3";
            AttemptRequest ar3 = attemptBuilder.buildFromStoredWorkflow(
                    rev,
                    wf1,
                    cf.create(),
                    ScheduleTime.runNow(sessionTime2),
                    Optional.of(retryAttemptName),
                    Optional.absent(),
                    ImmutableList.of());
            StoredSessionAttemptWithSession attempt3 = exec.submitWorkflow(0, ar3, def1);
            StoredSessionWithLastAttempt session2AfterRetry = store.getSessionById(attempt2.getSessionId());

            assertSessionAndLastAttemptEquals(session2AfterRetry, attempt3);
            assertThat(session2AfterRetry.getLastAttempt().getRetryAttemptName(), is(Optional.of(retryAttemptName)));
            assertThat(ImmutableList.of(session2AfterRetry, session1, otherProjSession1), is(store.getSessions(100, Optional.absent())));
            assertThat(ImmutableList.of(session2AfterRetry, session1), is(store.getSessionsOfProject(proj.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(session2AfterRetry, session1), is(store.getSessionsOfWorkflowByName(proj.getId(), wf1.getName(), 100, Optional.absent())));
            assertEmpty(store.getSessionsOfWorkflowByName(proj.getId(), wf2.getName(), 100, Optional.absent()));
            assertThat(store.getActiveAttemptsOfWorkflow(wf1.getId(), 100, Optional.absent()), containsInAnyOrder(attempt1, attempt3));
            assertThat(store.getActiveAttemptsOfWorkflow(wf2.getId(), 100, Optional.absent()), is(Matchers.empty()));

            SessionStore anotherSite = manager.getSessionStore(1);

            ////
            // manager internal getters
            //
            assertThat(attempt1, is(manager.getAttemptWithSessionById(attempt1.getId())));
            assertThat(attempt2, is(manager.getAttemptWithSessionById(attempt2.getId())));
            assertThat(attempt3, is(manager.getAttemptWithSessionById(attempt3.getId())));
            assertNotFound(() -> manager.getAttemptWithSessionById(attempt3.getId() + 10));

            ////
            // public sessions listings
            //
            assertThat(ImmutableList.of(session2AfterRetry, session1, otherProjSession1),
                    is(store.getSessions(100, Optional.absent())));
            assertThat(ImmutableList.of(session2AfterRetry, session1),
                    is(store.getSessions(2, Optional.absent())));
            assertThat(ImmutableList.of(session2AfterRetry),
                    is(store.getSessions(1, Optional.absent())));
            assertThat(ImmutableList.of(session1, otherProjSession1),
                    is(store.getSessions(100, Optional.of(session2AfterRetry.getId()))));
            assertThat(ImmutableList.of(otherProjSession1),
                    is(store.getSessions(100, Optional.of(session1.getId()))));
            assertEmpty(store.getSessions(100, Optional.of(otherProjSession1.getId())));
            assertEmpty(anotherSite.getSessions(100, Optional.absent()));

            ////
            // public attempt listings
            //
            assertThat(ImmutableList.of(attempt3, attempt1, otherProjAttempt1),
                    is(store.getAttempts(false, 100, Optional.absent())));
            assertThat(ImmutableList.of(attempt3, attempt1),
                    is(store.getAttempts(false, 2, Optional.absent())));
            assertThat(ImmutableList.of(attempt3),
                    is(store.getAttempts(false, 1, Optional.absent())));
            assertThat(ImmutableList.of(attempt1, otherProjAttempt1),
                    is(store.getAttempts(false, 100, Optional.of(attempt3.getId()))));
            assertEmpty(anotherSite.getAttempts(false, 100, Optional.absent()));

            assertThat(ImmutableList.of(attempt3, attempt2, attempt1, otherProjAttempt1),
                    is(store.getAttempts(true, 100, Optional.absent())));
            assertThat(ImmutableList.of(attempt3, attempt2),
                    is(store.getAttempts(true, 2, Optional.absent())));
            assertThat(ImmutableList.of(attempt3),
                    is(store.getAttempts(true, 1, Optional.absent())));
            assertThat(ImmutableList.of(attempt2, attempt1, otherProjAttempt1),
                    is(store.getAttempts(true, 100, Optional.of(attempt3.getId()))));
            assertEmpty(anotherSite.getAttempts(true, 100, Optional.absent()));

            assertThat(ImmutableList.of(attempt3, attempt1),
                    is(store.getAttemptsOfProject(false, proj.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(attempt3),
                    is(store.getAttemptsOfProject(false, proj.getId(), 1, Optional.absent())));
            assertThat(ImmutableList.of(attempt1),
                    is(store.getAttemptsOfProject(false, proj.getId(), 100, Optional.of(attempt3.getId()))));
            assertEmpty(anotherSite.getAttemptsOfProject(false, proj.getId(), 100, Optional.absent()));
            // TODO test with another project

            assertThat(ImmutableList.of(attempt3, attempt2, attempt1),
                    is(store.getAttemptsOfProject(true, proj.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(attempt3, attempt2),
                    is(store.getAttemptsOfProject(true, proj.getId(), 2, Optional.absent())));
            assertThat(ImmutableList.of(attempt2, attempt1),
                    is(store.getAttemptsOfProject(true, proj.getId(), 100, Optional.of(attempt3.getId()))));
            assertEmpty(anotherSite.getAttemptsOfProject(true, proj.getId(), 100, Optional.absent()));
            // TODO test with another project

            assertThat(ImmutableList.of(attempt3, attempt1),
                    is(store.getAttemptsOfWorkflow(false, wf1.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(attempt3),
                    is(store.getAttemptsOfWorkflow(false, wf1.getId(), 1, Optional.absent())));
            assertThat(ImmutableList.of(attempt1),
                    is(store.getAttemptsOfWorkflow(false, wf1.getId(), 100, Optional.of(attempt3.getId()))));
            assertEmpty(anotherSite.getAttemptsOfWorkflow(false, wf1.getId(), 100, Optional.absent()));
            assertEmpty(store.getAttemptsOfWorkflow(false, wf2.getId(), 100, Optional.absent()));
            // TODO test with another workflow

            assertThat(ImmutableList.of(attempt3, attempt2, attempt1),
                    is(store.getAttemptsOfWorkflow(true, wf1.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(attempt3, attempt2),
                    is(store.getAttemptsOfWorkflow(true, wf1.getId(), 2, Optional.absent())));
            assertThat(ImmutableList.of(attempt2, attempt1),
                    is(store.getAttemptsOfWorkflow(true, wf1.getId(), 100, Optional.of(attempt3.getId()))));
            assertEmpty(anotherSite.getAttemptsOfWorkflow(true, wf1.getId(), 100, Optional.absent()));
            assertEmpty(store.getAttemptsOfWorkflow(true, wf2.getId(), 100, Optional.absent()));
            // TODO test with another workflow

            StoredSessionAttempt rawAttempt1 = StoredSessionAttempt.copyOf(attempt1);
            StoredSessionAttempt rawAttempt2 = StoredSessionAttempt.copyOf(attempt2);
            StoredSessionAttempt rawAttempt3 = StoredSessionAttempt.copyOf(attempt3);

            assertThat(ImmutableList.of(rawAttempt1),
                    is(store.getAttemptsOfSession(session1.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(rawAttempt1),
                    is(store.getAttemptsOfSession(session1.getId(), 1, Optional.absent())));
            assertEmpty(store.getAttemptsOfSession(session1.getId(), 100, Optional.of(attempt1.getId())));
            assertEmpty(anotherSite.getAttemptsOfSession(session1.getId(), 100, Optional.absent()));

            assertThat(ImmutableList.of(rawAttempt3, rawAttempt2),
                    is(store.getAttemptsOfSession(session2.getId(), 100, Optional.absent())));
            assertThat(ImmutableList.of(rawAttempt3),
                    is(store.getAttemptsOfSession(session2.getId(), 1, Optional.absent())));
            assertThat(ImmutableList.of(rawAttempt2),
                    is(store.getAttemptsOfSession(session2.getId(), 100, Optional.of(rawAttempt3.getId()))));
            assertEmpty(anotherSite.getAttemptsOfSession(session2.getId(), 100, Optional.absent()));

            ////
            // public getters
            //
            assertThat(attempt1, is(store.getAttemptById(attempt1.getId())));
            assertThat(attempt2, is(store.getAttemptById(attempt2.getId())));
            assertNotFound(() -> store.getAttemptById(attempt1.getId() + 10));
            assertNotFound(() -> anotherSite.getAttemptById(attempt1.getId()));

            assertThat(attempt1, is(store.getAttemptByName(proj.getId(), wf1.getName(), sessionTime1, "")));
            assertThat(attempt2, is(store.getAttemptByName(proj.getId(), wf1.getName(), sessionTime2, "")));
            assertThat(attempt3, is(store.getAttemptByName(proj.getId(), wf1.getName(), sessionTime2, retryAttemptName)));
            assertNotFound(() -> store.getAttemptByName(proj.getId() + 10, wf1.getName(), sessionTime1, ""));
            assertNotFound(() -> store.getAttemptByName(proj.getId(), wf1.getName() + " ", sessionTime1, ""));
            assertNotFound(() -> store.getAttemptByName(proj.getId(), wf1.getName(), sessionTime1.plusSeconds(10000), ""));
            assertNotFound(() -> store.getAttemptByName(proj.getId(), wf1.getName(), sessionTime1, " "));
            assertNotFound(() -> anotherSite.getAttemptByName(proj.getId(), wf1.getName(), sessionTime1, ""));
            assertNotFound(() -> anotherSite.getAttemptByName(proj.getId(), wf1.getName(), sessionTime2, ""));

            assertThat(ImmutableList.of(attempt2, attempt3), is(store.getOtherAttempts(attempt2.getId())));
            assertThat(ImmutableList.of(attempt2, attempt3), is(store.getOtherAttempts(attempt3.getId())));

            ////
            // task archving
            //
            List<ArchivedTask> activeArchive = store.getTasksOfAttempt(attempt1.getId());
            SessionAttemptSummary sum = manager.lockAttemptIfExists(
                    attempt1.getId(),
                    (store, summary) -> {
                        store.aggregateAndInsertTaskArchive(attempt1.getId());
                        return summary;
                    }).get();
            assertThat(activeArchive, is(store.getTasksOfAttempt(attempt1.getId())));
            return null;
        });
    }

    private void assertSessionAndLastAttemptEquals(StoredSessionWithLastAttempt session, StoredSessionAttemptWithSession attempt)
    {
        assertThat(session.getId(), is(attempt.getSessionId()));
        assertThat(session.getUuid(), is(attempt.getSessionUuid()));
        assertThat(session.getLastAttemptId(), is(attempt.getId()));
        assertThat(session.getLastAttempt(), is(StoredSessionAttempt.copyOf(attempt)));
    }
}
