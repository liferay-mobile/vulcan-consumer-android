package com.liferay.vulcan.consumer

import com.github.kittinunf.result.Result
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.liferay.vulcan.consumer.model.Context
import com.liferay.vulcan.consumer.model.Relation
import com.liferay.vulcan.consumer.model.Thing
import com.liferay.vulcan.consumer.model.contextFrom
import com.liferay.vulcan.consumer.model.isId
import com.liferay.vulcan.consumer.model.merge
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.collections.Map.Entry
import kotlin.collections.set

fun fetch(
    url: HttpUrl, fields: Map<String, List<String>> = emptyMap(),
    embedded: List<String> = emptyList(),
    onComplete: (Result<Thing, Exception>) -> Unit) {

    //FIXME event oriented
    launch(UI) {
        asyncTask {
            val httpUrl = createUrl(url, fields, embedded)

            val credential = createAuthentication()

            val request = createRequest(httpUrl, credential)

            val response = OkHttpClient().newCall(request).execute()

            response.body()?.let {
                val (thing, embeddedThings) = parse(it.string())

                val nodes = embeddedThings.map { (id, embeddedThing) ->
                    val previousThing = graph[id]?.value

                    val newThing =
                        embeddedThing?.merge(previousThing) ?: previousThing

                    id to Node(id, newThing)
                }

                graph.putAll(nodes)

                Result.of(thing)
            } ?: Result.of { throw VulcanException("Not Found") }
        }.await().let(onComplete)
    }
}

private fun createRequest(httpUrl: HttpUrl?, credential: String?): Request =
    Request.Builder()
        .url(httpUrl)
        .addHeader("Authorization", credential)
        .addHeader("Accept", "application/ld+json")
        .build()

private fun createUrl(
    url: HttpUrl, fields: Map<String, List<String>>,
    embedded: List<String>): HttpUrl {

    return url.newBuilder()
        .apply {
            fields.forEach { (type, values) ->
                val types = values.joinToString(separator = ",")

                this.addQueryParameter("fields[$type]", types)
            }
        }
        .addQueryParameter("embedded", embedded.joinToString(","))
        .build()
}

class Node(val id: String, var value: Thing? = null)

var graph: MutableMap<String, Node> = mutableMapOf()

fun createAuthentication(): String? = Credentials.basic("test@liferay.com", "test")

fun parse(json: String): Pair<Thing, Map<String, Thing?>> {
    val mapType = TypeToken.getParameterized(
        Map::class.java, String::class.java, Any::class.java
    ).type

    val jsonObject = Gson().fromJson<Map<String, Any>>(json, mapType)

    return flatten(jsonObject, null)
}

private fun flatten(
    jsonObject: Map<String, Any>, parentContext: Context?):
    Pair<Thing, Map<String, Thing?>> {

    val id = jsonObject["@id"] as String

    val types = jsonObject["@type"] as List<String>

    val context =
        contextFrom(jsonObject["@context"] as? Map<String, Any>, parentContext)

    val (attributes, things) = jsonObject
        .filter { it.key !in listOf("@id", "@type", "@context") }
        .entries
        .fold(
            mutableMapOf<String, Any>() to mutableMapOf<String, Thing?>(),
            foldEntry(context))

    val thing = Thing(id, types, attributes)

    return thing to things
}

typealias FoldedAttributes =
Pair<MutableMap<String, Any>, MutableMap<String, Thing?>>

private fun foldEntry(context: Context?) = {
    acc: FoldedAttributes, entry: Entry<String, Any> ->

    val (attributes, things) = acc

    val key = entry.key
    val value = entry.value

    when {
        value is Map<*, *> -> (value as? Map<String, Any>)?.apply {
            val (thing, embeddedThings) = flatten(this, context)

            attributes[key] = Relation(thing.id)

            things.put(thing.id, thing)

            things.putAll(embeddedThings)
        }
        value is List<*> -> (value as? List<Map<String, Any>>)?.apply {
            val list = this.map { flatten(it, context) }

            val mutableList = mutableListOf<Relation>()

            for ((thing, embeddedThings) in list) {
                mutableList.add(Relation(thing.id))

                things.put(thing.id, thing)

                things.putAll(embeddedThings)
            }

            attributes[key] = mutableList
        }
        context != null && context.isId(key) -> with(value as String) {
            things[this] = null

            attributes[key] = Relation(this)
        }
        else -> attributes[key] = value
    }

    attributes to things
}

class VulcanException(s: String) : Throwable(s)

fun <T : Any> asyncTask(function: () -> Result<T, Exception>):
    Deferred<Result<T, Exception>> = async(CommonPool) { function() }