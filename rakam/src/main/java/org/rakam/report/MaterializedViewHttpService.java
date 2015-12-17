package org.rakam.report;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.MaterializedView;
import org.rakam.plugin.MaterializedViewService;
import org.rakam.plugin.ProjectItem;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.RakamHttpRequest;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;
import org.rakam.util.JsonResponse;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.rakam.server.http.HttpServer.errorMessage;

@Path("/materialized-view")
@Api(value = "/materialized-view", description = "Materialized View", tags = "materialized-view")
public class MaterializedViewHttpService extends HttpService {
    private final MaterializedViewService service;
    private final QueryHttpService queryService;

    @Inject
    public MaterializedViewHttpService(MaterializedViewService service, QueryHttpService queryService) {MaterializedViewService service1;
        service1 = service;
        this.service = service1;
        this.queryService = queryService;
    }

    @JsonRequest
    @ApiOperation(value = "List views")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.") })
    @Path("/list")
    public Object listViews(@ApiParam(name="project", required = true) String project) {
        if (project == null) {
            return errorMessage("project parameter is required", HttpResponseStatus.BAD_REQUEST);
        }

        return service.list(project);
    }

    @JsonRequest
    @ApiOperation(value = "Get schemas")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.") })
    @Path("/schema")
    public CompletableFuture<List<MaterializedViewSchema>> schema(@ApiParam(name="project", required = true) String project) {
        CompletableFuture<Map<String, List<SchemaField>>> schemas = service.getSchemas(project);

        return schemas.thenApply(schema -> schema.entrySet().stream()
                .map(entry -> new MaterializedViewSchema(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList()));
    }

    public static class MaterializedViewSchema {
        public final String name;

        public MaterializedViewSchema(String name, List<SchemaField> fields) {
            this.name = name;
            this.fields = fields;
        }

        public final List<SchemaField> fields;
    }

    /**
     * Creates a new materialized view for specified SQL query.
     * materialized views allow you to execute batch queries over the data-set.
     * Rakam caches the materialized view result and serve the cached data when you request.
     * You can also trigger an update using using '/view/update' endpoint.
     * This feature is similar to MATERIALIZED VIEWS in RDBMSs.
     *
     * curl 'http://localhost:9999/materialized-view/create' -H 'Content-Type: text/event-stream;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Yearly Visits", "query": "SELECT year(time), count(1) from visits GROUP BY 1"}'
     * @param query materialized view query
     * @return the status
     */
    @JsonRequest
    @ApiOperation(value = "Create view", authorizations = @Authorization(value = "master_key"))
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.") })
    @Path("/create")
    public JsonResponse create(@ParamBody MaterializedView query) {
        service.create(query);
        return JsonResponse.success();
    }

    @JsonRequest
    @ApiOperation(value = "Delete materialized view", authorizations = @Authorization(value = "master_key"))
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.") })
    @Path("/delete")
    public CompletableFuture<JsonResponse> delete(@ApiParam(name="project", required = true) String project,
                                                  @ApiParam(name="name", required = true) String name) {
        return service.delete(project, name)
                .thenApply(result -> JsonResponse.result(result.getError() == null));
    }

    /**
     * Invalidate previous cached data, executes the materialized view query and caches it.
     * This feature is similar to UPDATE MATERIALIZED VIEWS in RDBMSs.
     *
     *     curl 'http://localhost:9999/materialized-view/update' -H 'Content-Type: text/event-stream;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Yearly Visits"}'
     * @param request http request object
     */
    @GET
    @Path("/update")
    @ApiOperation(value = "Update view", authorizations = @Authorization(value = "master_key"))
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.") })
    public void update(RakamHttpRequest request) {
        queryService.handleServerSentQueryExecution(request, MaterializedViewRequest.class,
                query -> {
                    QueryExecution execution = service.lockAndUpdateView(service.get(query.project, query.name));
                    if(execution == null) {
                        QueryResult result = QueryResult.errorResult(new QueryError("There is another process that updates materialized view", null, 0, null));
                        return QueryExecution.completedQueryExecution(result);
                    }
                    return execution;
                });
    }

    public static class MaterializedViewRequest implements ProjectItem {
        public final String project;
        public final String name;

        public MaterializedViewRequest(String project, String name) {
            this.project = project;
            this.name = name;
        }

        @Override
        public String project() {
            return project;
        }
    }

    @JsonRequest
    @ApiOperation(value = "Get view")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.") })
    @Path("/get")
    public Object get(@ApiParam(name="project", required = true) String project,
                      @ApiParam(name="name", required = true) String name) {
        return service.get(project, name);
    }

}