package dev.scalar.trawler.server

import dev.scalar.trawler.server.graphql.QueryContext
import dev.scalar.trawler.server.graphql.schema
import graphql.GraphQL
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.common.WebEnvironment
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.ext.web.handler.graphql.GraphQLHandler
import io.vertx.ext.web.handler.graphql.GraphiQLHandler
import io.vertx.ext.web.handler.graphql.GraphiQLHandlerOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.apache.logging.log4j.LogManager
import java.util.*

class GraphQLApi : CoroutineVerticle() {
    val log = LogManager.getLogger()

    override suspend fun start() {
        val router = Router.router(vertx)
        vertx.exceptionHandler { e -> println(e)}
        router.route().handler(BodyHandler.create())

        val provider: JWTAuth = JWTAuth.create(
            vertx, JWTAuthOptions()
                .addPubSecKey(
                    PubSecKeyOptions()
                        .setAlgorithm("HS256")
                        .setBuffer("keyboard cat")
                )
        )

        if (WebEnvironment.development()) {
            val devToken = provider.generateToken(
                JsonObject(
                    mapOf(
                        "sub" to "devuser",
                        "project" to "63255f7a-e383-457a-9c30-4c7f95308749"
                    )
                )
            )

            val options = GraphiQLHandlerOptions().setEnabled(true)
                .setHeaders(mapOf("Authorization" to "Bearer $devToken"))
            router.route("/graphiql/*").handler(GraphiQLHandler.create(options))
        }

        router
            .errorHandler(500) { rc ->
                rc.failure().printStackTrace()
                rc.json(
                    mapOf("message" to rc.failure().message)
                )
                rc.fail(403)
            }
            .route()
            .handler(JWTAuthHandler.create(provider))
            .handler(
                GraphQLHandler.create(GraphQL.newGraphQL(schema).build())
                .queryContext { rc ->
                    QueryContext(rc.user(), UUID.fromString("63255f7a-e383-457a-9c30-4c7f95308749"))
                }
            )

        log.info("Starting graphql API")
        vertx.createHttpServer().requestHandler(router).listen(8080)
    }
}