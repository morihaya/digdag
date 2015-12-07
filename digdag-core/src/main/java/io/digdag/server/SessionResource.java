package io.digdag.server;

import java.util.List;
import java.util.stream.Collectors;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.PUT;
import javax.ws.rs.GET;
import com.google.inject.Inject;
import com.google.common.collect.*;
import com.google.common.base.Optional;
import io.digdag.core.session.SessionStore;
import io.digdag.core.session.SessionStoreManager;
import io.digdag.core.session.StoredSession;
import io.digdag.core.repository.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

@Path("/")
@Produces("application/json")
public class SessionResource
{
    // [*] GET  /api/sessions                                    # list sessions from recent to old
    // [*] GET  /api/sessions?repository=<name>                  # list sessions that belong to a particular repository
    // [*] GET  /api/sessions?repository=<name>&workflow=<name>  # list sessions that belong to a particular workflow
    // [*] GET  /api/sessions/{id}                               # show a session
    // [*] GET  /api/sessions/{id}/tasks                         # list tasks of a session
    // [ ] POST /api/sessions/{id}                               # do operations on a session (cancel, retry, etc.)

    private final RepositoryStoreManager rm;
    private final SessionStoreManager sm;

    private int siteId = 0;  // TODO get site id from context

    @Inject
    public SessionResource(
            RepositoryStoreManager rm,
            SessionStoreManager sm)
    {
        this.rm = rm;
        this.sm = sm;
    }

    @GET
    @Path("/api/sessions")
    public List<RestSession> getSessions(@QueryParam("repository") String repoName, @QueryParam("workflow") String wfName)
        throws ResourceNotFoundException
    {
        // TODO paging
        List<StoredSession> sessions;
        RepositoryStore rs = rm.getRepositoryStore(siteId);
        SessionStore ss = sm.getSessionStore(siteId);
        if (repoName == null) {
            sessions = ss.getSessions(100, Optional.absent());
        }
        else {
            StoredRepository repo = rs.getRepositoryByName(repoName);
            if (wfName == null) {
                sessions = ss.getSessionsOfRepository(repo.getId(), 100, Optional.absent());
            }
            else {
                StoredWorkflowSource wf = rs.getWorkflowByName(repo.getId(), wfName);
                sessions = ss.getSessionsOfWorkflow(wf.getId(), 100, Optional.absent());
            }
        }

        return sessions.stream()
            .map(s -> RestSession.of(s))
            .collect(Collectors.toList());
    }

    @GET
    @Path("/api/sessions/{id}")
    public RestSession getSessions(@PathParam("id") long id)
        throws ResourceNotFoundException
    {
        StoredSession s = sm.getSessionStore(siteId)
            .getSessionById(id);
        return RestSession.of(s);
    }

    @GET
    @Path("/api/sessions/{id}/tasks")
    public List<RestTask> getTasks(@PathParam("id") long id)
    {
        // TODO paging
        return sm.getSessionStore(siteId)
            .getTasks(id, 100, Optional.absent())
            .stream()
            .map(task -> RestTask.of(task))
            .collect(Collectors.toList());
    }
}
