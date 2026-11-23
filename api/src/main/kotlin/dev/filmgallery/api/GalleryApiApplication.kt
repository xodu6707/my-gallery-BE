package dev.filmgallery.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class GalleryApiApplication

fun main(args: Array<String>) {
	runApplication<GalleryApiApplication>(*args)
}
