package com.ammaraskar.intent.fuzz

import jadx.api.JadxDecompiler
import javax.xml.parsers.DocumentBuilderFactory

class IntentReceiver(
    val receiverType: String,
    val componentName: String,
    val actions: Collection<String>,
    val categories: Collection<String>,
    val datas: Collection<String>,
    val aliasTarget: String?
) {
    override fun equals(other: Any?): Boolean {
        if (other !is IntentReceiver) {
            return false
        }
        return receiverType == other.receiverType &&
                componentName == other.componentName &&
                actions == other.actions &&
                categories == other.categories &&
                datas == other.datas &&
                aliasTarget == other.aliasTarget
    }
}

class DataSet(
    val schemes : MutableSet<String> = mutableSetOf(),
    val hosts : MutableSet<String> = mutableSetOf(),
    val ports : MutableSet<String> = mutableSetOf(),
    val paths : MutableSet<String> = mutableSetOf(),
    val pathPatterns : MutableSet<String> = mutableSetOf(),
    val pathPrefixs : MutableSet<String> = mutableSetOf(),
    val pathSuffixs : MutableSet<String> = mutableSetOf(),
    val pathAdvancedPatterns : MutableSet<String> = mutableSetOf(),
    val mimeTypes : MutableSet<String> = mutableSetOf(),
) {
    fun makeDeeplinkUrls() : List<String> {
        if (schemes.isEmpty()) return emptyList()

        val maximum = 50
        val hostPool = if(hosts.isEmpty()) setOf("") else hosts
        val portPool = if(ports.isEmpty()) setOf("") else ports
        val pathPool = samplePaths()

        val out = mutableListOf<String>()
        loop@ for (scheme in schemes) {
            for (host in hostPool) {
                for (port in portPool) {
                    for (p in pathPool) {
                        out += buildUri(scheme, host, port, p)
                        if (out.size >= maximum) break@loop
                    }
                }
            }
        }

        return out.distinct()
    }

    private fun samplePaths(): List<String> {
        val acc = linkedSetOf<String>()

        // exact paths
        paths.forEach { acc += normalizePath(it) }

        // prefixes → 대표 케이스 몇 개
        pathPrefixs.forEach { pref ->
            val n = normalizePath(pref)
            acc += listOf(
                n,                                // 딱 prefix
                if (n.endsWith("/")) "${n}x" else "$n/x", // 바로 뒤에 한 단계
                "$n/a/b"                          // 깊은 경로
            )
        }

        // suffixes → 대표 케이스 몇 개
        pathSuffixs.forEach { suf ->
            acc += listOf("/a$suf", "/deep/path$suf")
        }

        // simple patterns(glob 느낌) → 대충 매칭되는 샘플 2개
        pathPatterns.forEach { pat ->
            acc += samplesForSimplePattern(pat)
        }

        // advanced patterns(API 31+) → 여기선 간단히 같은 방식으로 샘플
        pathAdvancedPatterns.forEach { ap ->
            acc += samplesForAdvancedPattern(ap)
        }

        // 경로 제약이 하나도 없으면 빈 경로 허용
        if (acc.isEmpty()) acc += ""

        return acc.toList()
    }

    private fun normalizePath(p: String): String {
        if (p.isEmpty()) return ""
        return if (p.startsWith("/")) p else "/$p"
    }

    private fun samplesForSimplePattern(pattern: String): List<String> {
        // 매우 단순한 샘플러: ".*" → "abc", "*" → "x", "." → "a"
        val s1 = normalizePath(
            pattern.replace(".*", "abc").replace("*", "x").replace(".", "a")
        )
        val s2 = normalizePath(
            pattern.replace(".*", "").replace("*", "").replace(".", "z")
        )
        return listOf(s1, s2).distinct()
    }

    private fun samplesForAdvancedPattern(pattern: String): List<String> {
        // 고급 패턴도 일단 비슷하게 샘플 2개 생성 (필요시 정교화)
        val s1 = normalizePath(pattern.replace("*", "x").replace(".", "a"))
        val s2 = normalizePath(pattern.replace("*", "").replace(".", "z"))
        return listOf(s1, s2).distinct()
    }

    private fun buildUri(scheme: String, host: String, port: String, path: String): String {
        val hasAuthority = host.isNotEmpty() || port.isNotEmpty()
        val authority = when {
            host.isEmpty() -> ""
            port.isEmpty() -> host
            else -> "$host:$port"
        }
        val pathPart = when {
            path.isEmpty() -> ""
            path.startsWith("/") -> path
            else -> "/$path"
        }

        return if (hasAuthority) {
            // authority 있는 정규형: scheme://host[:port]/path
            "$scheme://$authority$pathPart"
        } else {
            // authority 미지정: scheme:/path  (또는 scheme: 만)
            if (pathPart.isEmpty()) "$scheme:" else "$scheme:$pathPart"
        }
    }
}

fun parseIntentReceiversFromManifest(manifestXML: String, decompiler: JadxDecompiler): List<IntentReceiver> {
    val targets = mutableListOf<IntentReceiver>()

    val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val document = documentBuilder.parse(manifestXML.byteInputStream())

    val manifestElement = document.getElementsByTagName("manifest")
    if (manifestElement.length != 1) {
        throw IllegalArgumentException("AndroidManifest contained ${manifestElement.length} manifest elements")
    }
    val packageName = manifestElement.item(0).attributes.getNamedItem("package")?.nodeValue
        ?: throw IllegalArgumentException("<manifest> element did not contain package")

    // Find all <intent-filter> tags and iterate over them.
    val intentFilters = document.getElementsByTagName("intent-filter")
    for (i in 0 until intentFilters.length) {
        val node = intentFilters.item(i)

        val containingComponent = node.parentNode

        // Get the class name of the component containing this intent-filter.
        val intentClass = containingComponent.attributes.getNamedItem("android:name")?.nodeValue
        if (intentClass == null) {
            println("Skipping intent receiver because it doesn't have an android:name attribute")
            continue
        }

        // Check to see if the component is exported. That is, either the "android:exported" attribute is marked as
        // "true" or if it isn't present, it takes a default value of true when there is an <intent-filter>
        val exportedAttribute =
            containingComponent.attributes.getNamedItem("android:exported")?.nodeValue
                ?: "true"
        val isExported = exportedAttribute == "true"

        // Only add to list of targets if it is exported.
        if (!isExported) {
            continue
        }

        // Check if this is an alias.
        val aliasTargetActivity = if (containingComponent.nodeName == "activity-alias") {
            containingComponent.attributes.getNamedItem("android:targetActivity")?.nodeValue
                ?: throw IllegalArgumentException("Manifest has an activity-alias without an android:targetActivity")
        } else {
            null
        }

        val actionNames = mutableListOf<String>()
        val categoryNames = mutableListOf<String>()
        val dataSet = DataSet()
        // Gather all the action tags.
        for (j in 0 until node.childNodes.length) {
            val intentFilterChild = node.childNodes.item(j)

            // Check if this is a <category> or an <action>
            if (intentFilterChild.nodeName == "action") {
                actionNames.add(intentFilterChild.attributes.getNamedItem("android:name").nodeValue)
            } else if (intentFilterChild.nodeName == "category") {
                categoryNames.add(intentFilterChild.attributes.getNamedItem("android:name").nodeValue)
            } else if (intentFilterChild.nodeName == "data") {
                intentFilterChild.attributes.getNamedItem("android:scheme")?.nodeValue?.let(dataSet.schemes::add)
                intentFilterChild.attributes.getNamedItem("android:host")?.nodeValue?.let(dataSet.hosts::add)
                intentFilterChild.attributes.getNamedItem("android:port")?.nodeValue?.let(dataSet.ports::add)
                intentFilterChild.attributes.getNamedItem("android:path")?.nodeValue?.let(dataSet.paths::add)
                intentFilterChild.attributes.getNamedItem("android:pathPattern")?.nodeValue?.let(dataSet.pathPatterns::add)
                intentFilterChild.attributes.getNamedItem("android:pathPrefix")?.nodeValue?.let(dataSet.pathPrefixs::add)
                intentFilterChild.attributes.getNamedItem("android:pathSuffix")?.nodeValue?.let(dataSet.pathSuffixs::add)
                intentFilterChild.attributes.getNamedItem("android:pathAdvancedPattern")?.nodeValue?.let(dataSet.pathAdvancedPatterns::add)
                intentFilterChild.attributes.getNamedItem("android:mimeType")?.nodeValue?.let(dataSet.mimeTypes::add)
            }
        }

        val dataUrls : List<String> = dataSet.makeDeeplinkUrls()
        if (dataUrls.size == 0) { // if don't have deeplink urls
            continue
        }

        val receiverType = when (containingComponent.nodeName) {
            "activity" -> "Activity"
            "activity-alias" -> "Activity"
            "service" -> continue  // We do not support fuzzing services for now
            "receiver" -> "BroadcastReceiver"
            "provider" -> continue  // We do not support content providers for now
            else -> throw IllegalArgumentException("Unknown component type: ${containingComponent.nodeName}")
        }

        // Create the intent receiver object.
        val intentReceiver = IntentReceiver(
            receiverType = receiverType,
            componentName = "$packageName/$intentClass",
            actions = actionNames,
            categories = categoryNames,
            datas = dataUrls,
            aliasTarget = aliasTargetActivity
        )

        // Only add alias if it has new actions or categories.
        if (aliasTargetActivity != null) {
            val existingTargets = targets.filter {
                it.componentName == "$packageName/$aliasTargetActivity" || it.aliasTarget == aliasTargetActivity
            }

            // We skip this alias if there already exists one that covers the same actions and categories.
            if (existingTargets.any {
                    it.actions.containsAll(intentReceiver.actions) && it.categories.containsAll(intentReceiver.categories)
                }) {
                println("Skipping alias: ${intentReceiver.componentName} with target $aliasTargetActivity")
                continue
            }
        }

        // Skip duplicate intent receivers (i.e., all properties are the same).
        // Duplicates happen because an activity can declare multiple intent filters.
        // They may have different <data> tags, which we do not parse (yet?).
        // Might be helpful to use them since they hint at the scheme and host of the URI.
        if (targets.contains(intentReceiver)) {
            println("Skipping duplicate intent receiver: ${intentReceiver.componentName}")
            continue
        }

        println("Adding intent receiver: ${intentReceiver.componentName}")
        targets.add(intentReceiver)
    }

    return targets
}
