package dev.filmgallery.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

@SpringBootTest
class GalleryApiApplicationTests {

    @Autowired
    lateinit var repository: StackProbeRepository

	@Test
	fun contextLoads() {
		assertEquals("Local stack smoke check", repository.findById(1).orElseThrow().label)
	}

}
