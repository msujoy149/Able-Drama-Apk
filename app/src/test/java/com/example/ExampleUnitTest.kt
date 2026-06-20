package com.example

import com.example.ui.DetectedResource
import com.example.util.VideoAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testVideoAnalyzerResolutionSizeEstimation() {
    // Verify that calculation is deterministic and size differs by resolution
    val size144p = VideoAnalyzer.calculateEstimatedSize("144p", 300.0, "mp4")
    val size1080p = VideoAnalyzer.calculateEstimatedSize("1080p", 300.0, "mp4")
    
    assertTrue(size144p > 0)
    assertTrue(size1080p > size144p)
    
    // Verify specific expected display sizes format
    val formatted1080p = VideoAnalyzer.formatFileSize(size1080p)
    assertTrue(formatted1080p.contains("MB") || formatted1080p.contains("GB"))
  }

  @Test
  fun testVideoAnalyzerAvailableQualitiesResolutionBasedExpansion() = runBlocking {
    // 1. Single file detection test (e.g. Google Drive, direct mp4, etc.)
    val singleResource = listOf(
      DetectedResource(
        url = "https://drive.google.com/file/d/abc/view",
        title = "My Video",
        fileType = "Video",
        quality = "720p",
        fileSize = 8200000L
      )
    )
    val singleOptions = VideoAnalyzer.analyze(singleResource, 120.0)
    assertEquals(1, singleOptions.size)
    assertEquals("720p", singleOptions.first().resolution)
    assertEquals(8200000L, singleOptions.first().sizeBytes)
    assertEquals("7.8 MB", singleOptions.first().displaySize)

    // 2. Multi-stream platform test (should return generated standard resolutions as requested)
    val multiResources = listOf(
      DetectedResource(
        url = "https://www.googlevideo.com/videoplayback?itag=37",
        title = "Adele - Hello",
        fileType = "Video",
        quality = "1080p",
        fileSize = 0L
      ),
      DetectedResource(
        url = "https://www.googlevideo.com/videoplayback?itag=22",
        title = "Adele - Hello",
        fileType = "Video",
        quality = "720p",
        fileSize = 0L
      )
    )
    val multiOptions = VideoAnalyzer.analyze(multiResources, 300.0)
    assertTrue(multiOptions.isNotEmpty())
    val resolutions = multiOptions.map { it.resolution }
    assertTrue(resolutions.any { it.contains("1080") })
    assertTrue(resolutions.any { it.contains("720") })

    // Check sorting descending by resolution priority
    val firstResolution = multiOptions.first().resolution
    assertTrue(firstResolution.contains("1080"))
    
    // Check that there are absolutely no duplicate options in resolution
    assertEquals(multiOptions.size, multiOptions.distinctBy { it.resolution }.size)
  }
}
