package dev.scalar.trawler.server.graphql

import dev.scalar.trawler.ontology.FacetMetaType
import dev.scalar.trawler.server.db.EntityType
import dev.scalar.trawler.server.db.FacetType
import dev.scalar.trawler.server.db.FacetValue
import dev.scalar.trawler.server.db.util.ilike
import dev.scalar.trawler.ontology.Ontology
import dev.scalar.trawler.server.ontology.OntologyCache
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class EntityQuery {
    private suspend fun fetchEntities(ids: List<UUID>): List<Entity> =
        newSuspendedTransaction {
            FacetValue
                .join(FacetType, JoinType.INNER, FacetType.id, FacetValue.typeId)
                .join(dev.scalar.trawler.server.db.Entity, JoinType.INNER, dev.scalar.trawler.server.db.Entity.id, FacetValue.entityId)
                .join(dev.scalar.trawler.server.db.EntityType, JoinType.LEFT, FacetValue.entityTypeId, dev.scalar.trawler.server.db.EntityType.id)
                .select {
                    FacetValue.entityId.inList(ids)
                }
                .groupBy { it[FacetValue.entityId] }
                .map {
                    val type = it.value.map { it[dev.scalar.trawler.server.db.EntityType.uri] }
                        .filterNotNull()
                        .first()

                    val typeName = it.value.map { it[dev.scalar.trawler.server.db.EntityType.name] }
                        .filterNotNull()
                        .first()

                    val relationships =
                        it.value
                            .filter { it[FacetType.metaType] == FacetMetaType.RELATIONSHIP.value }
                            .groupBy { it[FacetType.uri] }
                            .map {
                                val name = it.value.first()[FacetType.name]
                                val version = it.value.first()[FacetValue.version]
                                val metaType = it.value.first()[FacetType.metaType]

                                Facet(
                                    it.key,
                                    name,
                                    metaType,
                                    version,
                                    it.value.map { it[FacetValue.targetEntityId] }
                                )
                            }

                    val others = it.value
                        .filter {
                            it[FacetType.metaType] != FacetMetaType.RELATIONSHIP.value &&
                                    it[EntityType.uri] == null
                        }
                        .groupBy { it[FacetType.uri] }
                        .map {
                            val name = it.value.first()[FacetType.name]
                            val version = it.value.first()[FacetValue.version]
                            val metaType = it.value.first()[FacetType.metaType]

                            Facet(
                                it.key,
                                name,
                                metaType,
                                version,
                                it.value.map { it[FacetValue.value] }
                            )
                        }
                    Entity(
                        it.key,
                        type,
                        typeName,
                        relationships + others
                    )
                }
        }

    data class Filter(
        val uri: String,
        val value: String
    )

    private fun filterToOp(columnSet: Alias<FacetValue>, ontology: Ontology, filter: Filter): Op<Boolean> {
        val facetType = ontology.facetTypeByUri(filter.uri)!!

        return columnSet[FacetValue.typeId].eq(facetType.id) and when (facetType.metaType) {
            FacetMetaType.STRING -> {
                columnSet[FacetValue.value].castTo<String>(TextColumnType()).ilike("%${filter.value}%")
            }
            FacetMetaType.TYPE_REFERENCE -> {
                val entityType = ontology.entityTypeByUri(filter.value)
                columnSet[FacetValue.entityTypeId].eq(entityType!!.id)
            }
            else -> {
                throw NotImplementedError()
            }
        }
    }

    suspend fun search(context: QueryContext, filters: List<Filter>): List<Entity> {
        val ontology = OntologyCache.CACHE[context.projectId]

        val ids = transaction {

            val aliases = filters.mapIndexed { index, filter ->
                filter.uri to FacetValue.alias("filter_${index}")
            }.associate { it.first to it.second }

            val firstAlias = aliases[filters[0].uri]!!
            var query: ColumnSet = firstAlias

            filters.drop(1).forEachIndexed { index, filter ->
                val alias = aliases[filter.uri]!!
                query = query.innerJoin(alias, { firstAlias[FacetValue.entityId] }, { alias[FacetValue.entityId] })
            }

            query
                .slice(firstAlias[FacetValue.entityId])
                .select {
                    val firstFilter = filters[0]
                    val initialOp = filterToOp(aliases[firstFilter.uri]!!, ontology, firstFilter)

                    filters.drop(1).fold(initialOp) { op, filter ->
                        op.and(
                            filterToOp(
                                aliases[filter.uri]!!,
                                ontology,
                                filter
                            )
                        )
                    }
                }
                .map { row -> row[firstAlias[FacetValue.entityId]] }
        }

        return fetchEntities(ids)
    }

    suspend fun entity(id: UUID) = fetchEntities(listOf(id)).firstOrNull()

    suspend fun entityGraph(id: UUID, d: Int): List<Entity> {
        var currentEntities = fetchEntities(listOf(id))
        val output = currentEntities.toMutableList()

        for (i in 0 until d) {
            val targetIds = currentEntities.flatMap { entity ->
                entity.facets.filter {
                    it.metaType == FacetMetaType.RELATIONSHIP.value
                }
                .flatMap { (it.value as List<UUID>) }
            }

            val fromIds = transaction {
                FacetValue
                    .join(dev.scalar.trawler.server.db.Entity, JoinType.INNER, dev.scalar.trawler.server.db.Entity.id, FacetValue.entityId)
                    .slice(dev.scalar.trawler.server.db.Entity.id)
                    .select { FacetValue.targetEntityId.inList(currentEntities.map { it.entityId }) }
                    .map { it[dev.scalar.trawler.server.db.Entity.id].value }
            }

            val existingIds = output.map { it.entityId }.toSet()

            val idsToFetch = (targetIds + fromIds).filter { !existingIds.contains(it) }

            currentEntities = fetchEntities(idsToFetch)
            output.addAll(currentEntities)
        }

        return output
    }
}