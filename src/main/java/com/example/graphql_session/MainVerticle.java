package com.example.graphql_session;

import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.*;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.htpasswd.HtpasswdAuth;
import io.vertx.ext.auth.htpasswd.HtpasswdAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.graphql.GraphQLHandler;
import io.vertx.ext.web.handler.graphql.GraphiQLHandler;
import io.vertx.ext.web.handler.graphql.GraphiQLHandlerOptions;
import io.vertx.ext.web.handler.graphql.VertxPropertyDataFetcher;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class MainVerticle extends AbstractVerticle {

  public static void main(String[] args) {
    Vertx.vertx().deployVerticle(new MainVerticle());
  }

  private HtpasswdAuth auth;

  @Override
  public void start() {
    auth = HtpasswdAuth.create(vertx, new HtpasswdAuthOptions().setHtpasswdFile("users"));

    Router router = Router.router(vertx);

    SessionStore store = LocalSessionStore.create(vertx);
    SessionHandler sessionHandler = SessionHandler.create(store);
    router.route().handler(sessionHandler);

    GraphQL graphQL = setupGraphQLJava();
    router.route("/graphql").handler(GraphQLHandler.create(graphQL));

    GraphiQLHandlerOptions options = new GraphiQLHandlerOptions()
      .setEnabled(true);
    router.route("/graphiql/*").handler(GraphiQLHandler.create(options));

    vertx.createHttpServer().requestHandler(router).listen(8080);
  }

  private GraphQL setupGraphQLJava() {
    String schema = vertx.fileSystem().readFileBlocking("schema.graphqls").toString();

    SchemaParser schemaParser = new SchemaParser();
    TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema);

    RuntimeWiring runtimeWiring = RuntimeWiring.newRuntimeWiring()
      .type("Query", builder -> builder
        .dataFetcher("userPrefs", this::userPrefs)
      )
      .type("Mutation", builder -> builder
        .dataFetcher("login", this::login)
        .dataFetcher("logout", this::logout)
      )
      .wiringFactory(new WiringFactory() {
        @Override
        public DataFetcher<?> getDefaultDataFetcher(FieldWiringEnvironment environment) {
          return new VertxPropertyDataFetcher(environment.getFieldDefinition().getName());
        }
      })
      .build();

    SchemaGenerator schemaGenerator = new SchemaGenerator();
    GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

    return GraphQL.newGraphQL(graphQLSchema)
      .build();
  }

  private JsonArray userPrefs(DataFetchingEnvironment env) {
    RoutingContext rc = env.getContext();
    User user = Objects.requireNonNull(rc.user(), "Not logged in");
    String username = user.principal().getString("username");
    if ("john".equals(username)) {
      return new JsonArray().add("apple").add("banana");
    } else {
      return new JsonArray().add("banana").add("pineapple");
    }
  }

  private CompletionStage<Boolean> login(DataFetchingEnvironment env) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();

    String username = Objects.requireNonNull(env.getArgument("user"), "user is null");
    String password = Objects.requireNonNull(env.getArgument("password"), "password is null");

    RoutingContext rc = env.getContext();
    Session session = rc.session();
    JsonObject authInfo = new JsonObject()
      .put("username", username)
      .put("password", password);
    auth.authenticate(authInfo, ar -> {
      if (ar.succeeded()) {
        User user = ar.result();
        rc.setUser(user);
        if (session != null) {
          session.regenerateId();
        }
        future.complete(true);
      } else {
        ar.cause().printStackTrace();
        future.complete(false);
      }
    });
    return future;
  }

  private boolean logout(DataFetchingEnvironment env) {
    RoutingContext rc = env.getContext();
    rc.clearUser();
    rc.session().destroy();
    return true;
  }
}
