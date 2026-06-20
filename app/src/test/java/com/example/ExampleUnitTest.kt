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
    val mockResources = listOf(
      DetectedResource(
        url = "https://www.googlevideo.com/videoplayback?itag=37",
        title = "Adele - Hello",
        fileType = "Video",
        quality = "1080p",
        fileSize = 0L
      )
    )
    
    val options = VideoAnalyzer.analyze(mockResources, 300.0)
    
    // Check that we have multiple dynamic resolution steps up to 1080p
    assertTrue(options.isNotEmpty())
    val resolutions = options.map { it.resolution }
    assertTrue(resolutions.contains("1080p"))
    assertTrue(resolutions.contains("720p"))
    assertTrue(resolutions.contains("480p"))
    assertTrue(resolutions.contains("360p"))
    assertTrue(resolutions.contains("240p"))
    assertTrue(resolutions.contains("144p"))
    
    // Check sorting descending by resolution priority
    val firstResolution = options.first().resolution
    assertEquals("1080p", firstResolution)
    
    // Check that there are absolutely no duplicate options in resolution
    assertEquals(options.size, options.distinctBy { it.resolution }.size)
    
    // Check size calculation uniqueness - every resolution must have a distinct calculated size
    val sizes = options.map { it.sizeBytes }
    assertEquals(sizes.size, sizes.distinct().size)
  }
}
