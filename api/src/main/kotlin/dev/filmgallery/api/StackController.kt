package dev.filmgallery.api

import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@Entity
class StackProbe(@Id var id: Long = 0, var label: String = "")

interface StackProbeRepository : JpaRepository<StackProbe, Long>

@RestController
class StackController(private val repository: StackProbeRepository) {
    @GetMapping("/api/stack")
    fun stack(): Map<String, Any> = mapOf("status" to "UP", "database" to "UP", "records" to repository.count())
}
