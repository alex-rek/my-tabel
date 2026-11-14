package ru.mytabel.app.domain

import org.junit.Test
import org.junit.Assert.*
import ru.mytabel.app.updates.*

class ReleaseCatalogTest {
    private fun release(version:String,draft:Boolean=false,size:Long=100,digest:String="sha256:"+"a".repeat(64),name:String="MyTabel-$version-debug.apk")="""{"tag_name":"v$version","draft":$draft,"prerelease":true,"body":"Changes","assets":[{"id":12,"name":"$name","size":$size,"digest":"$digest","state":"uploaded"}]}"""
    @Test fun versionsCompareNumericallyAndRejectInvalidTags(){
        assertTrue(AppVersion.parse("0.10.0")!!>AppVersion.parse("v0.9.9")!!)
        listOf("v1.2","1.2.3-beta","../1.2.3","999999999999.2.3").forEach{assertNull(AppVersion.parse(it))}
    }
    @Test fun choosesNewestPublishedApkEvenWithGitHubPrereleaseFlag(){
        val found=ReleaseCatalog.newest("[${release("0.4.1")},${release("0.10.0")},${release("0.9.0")}]","0.4.2")!!
        assertEquals("v0.10.0",found.tag);assertEquals(12L,found.assetId)
        assertNull(ReleaseCatalog.newest("[${release("0.4.2")},${release("0.4.1")}]","0.4.2"))
    }
    @Test fun ignoresDraftsUnrelatedAssetsAndMissingIntegrityMetadata(){
        val json="[${release("1.0.0",draft=true)},${release("1.1.0",digest="")},${release("1.2.0",size=150_000_001)},${release("1.3.0",name="other.apk")},${release("0.5.0")}]"
        assertEquals("v0.5.0",ReleaseCatalog.newest(json,"0.4.2")!!.tag)
    }
    @Test fun emptyCatalogHasNoUpdate(){assertNull(ReleaseCatalog.newest("[]","0.4.2"))}
}
