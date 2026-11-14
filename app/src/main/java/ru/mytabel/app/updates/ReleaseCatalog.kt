package ru.mytabel.app.updates

import com.google.gson.JsonParser

data class AppVersion(val major:Int,val minor:Int,val patch:Int):Comparable<AppVersion> {
    override fun compareTo(other:AppVersion)=compareValuesBy(this,other,{it.major},{it.minor},{it.patch})
    companion object {
        fun parse(value:String):AppVersion? {
            val match=Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value) ?: return null
            val numbers=match.groupValues.drop(1).map{it.toIntOrNull() ?: return null}
            return AppVersion(numbers[0],numbers[1],numbers[2])
        }
    }
}
data class UpdateRelease(val tag:String,val version:AppVersion,val notes:String,val assetId:Long,val size:Long,val sha256:String)
object ReleaseCatalog {
    const val REPOSITORY="alex-rek/my-tabel"
    const val MAX_APK_BYTES=150_000_000L
    // Existing published builds use GitHub's prerelease flag. Drafts are never offered.
    fun newest(json:String,current:String):UpdateRelease? {
        val installed=requireNotNull(AppVersion.parse(current))
        return JsonParser.parseString(json).asJsonArray.mapNotNull{element->
            runCatching {
                val r=element.asJsonObject
                if(r.get("draft")?.asBoolean==true)return@runCatching null
                val tag=r.get("tag_name").asString
                val version=AppVersion.parse(tag) ?: return@runCatching null
                if(version<=installed)return@runCatching null
                val asset=r.getAsJsonArray("assets").firstOrNull{
                    val a=it.asJsonObject
                    a.get("name")?.asString=="MyTabel-${tag.removePrefix("v")}-debug.apk"&&a.get("state")?.asString=="uploaded"
                }?.asJsonObject ?: return@runCatching null
                val size=asset.get("size").asLong;val id=asset.get("id").asLong
                val digest=asset.get("digest")?.takeUnless{it.isJsonNull}?.asString.orEmpty()
                if(size !in 1..MAX_APK_BYTES||id<=0||!Regex("sha256:[0-9a-fA-F]{64}").matches(digest))return@runCatching null
                UpdateRelease(tag,version,r.get("body")?.takeUnless{it.isJsonNull}?.asString.orEmpty().take(12000),id,size,digest.removePrefix("sha256:").lowercase())
            }.getOrNull()
        }.maxByOrNull{it.version}
    }
}
