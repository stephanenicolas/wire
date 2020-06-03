/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.gradle

import com.squareup.wire.gradle.WireExtension.ProtoRootSet
import com.squareup.wire.schema.Location
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.FileOrUriNotationConverter
import org.gradle.api.logging.Logger
import java.io.File
import java.net.URI

/**
 * Builds Wire's inputs (expressed as [Location] lists) from Gradle's objects (expressed as
 * directory trees, jars, and coordinates). This includes registering dependencies with the project
 * so they can be resolved for us.
 */
internal class WireInput(var configuration2: Configuration) {
  val name: String
    get() = configuration2.name

  private val dependencyToIncludes = mutableMapOf<String, List<String>>()
  private val locationList = mutableListOf<Location>()
  private val parser = FileOrUriNotationConverter.parser()

  fun addPaths(project: Project, paths: Set<String>) {
    for (path in paths) {
      println("path added: $path")
        val converted = parser.parseNotation(path)
        if (converted is File) {
            val file = if (!converted.isAbsolute) File(project.projectDir, converted.path) else converted
            check(file.exists()) { "Invalid path string: \"$path\". Path does not exist." }
            if(!file.isJar && !file.isDirectory) {
                throw IllegalArgumentException("""
                    |Invalid path string: "$path".
                    |For individual files, use the following syntax:
                    |wire {
                    |  sourcePath {
                    |    srcDir 'dirPath'
                    |    include 'relativePath'
                    |  }
                    |}
                    """.trimMargin())
            }
            locationList.add(Location.get(project.file(path).path))
        } else if (converted is URI && isURL(converted)) {
            throw IllegalArgumentException(
                    "Invalid path string: \"$path\". URL dependencies are not allowed."
            )
        } else {
            val dependency = project.dependencies.create(path)
            configuration2.dependencies.add(dependency)
        }
    }
  }

  fun addJars(project: Project, jars: Set<ProtoRootSet>) {
    for (jar in jars) {
      jar.srcJar?.let { path ->
        println("jar added: $path")
        val dependency = resolveDependency(project, path)
        dependencyToIncludes[dependency.id] = jar.includes

        val converted = parser.parseNotation(path)
        if (converted is File) {
            val file = if (!converted.isAbsolute) File(project.projectDir, converted.path) else converted
            check(file.exists()) { "Invalid path string: \"$path\". Path does not exist." }
            if(!file.isJar && !file.isDirectory) {
                throw IllegalArgumentException("""
                    |Invalid path string: "$path".
                    |For individual files, use the following syntax:
                    |wire {
                    |  sourcePath {
                    |    srcDir 'dirPath'
                    |    include 'relativePath'
                    |  }
                    |}
                    """.trimMargin())
            }
          if(jar.includes.isEmpty()) {
            locationList.add(Location.get(project.file(path).path))
          } else {
            locationList.addAll(jar.includes.map { include -> Location.get(base = project.file(path).path, path = include)})
          }
        } else if (converted is URI && isURL(converted)) {
          throw IllegalArgumentException(
                  "Invalid path string: \"$path\". URL dependencies are not allowed."
          )
      } else {
          val dependency = project.dependencies.create(path)
          configuration2.dependencies.add(dependency)
        }
      }
    }
  }

  fun addTrees(project: Project, trees: Set<SourceDirectorySet>) {
    for (tree in trees) {
        // TODO: this eagerly resolves dependencies; fix this!
        tree.srcDirs.forEach {
            check(it.exists()) {
                "Invalid path string: \"${it.relativeTo(project.projectDir)}\". Path does not exist."
            }
        }
        println("tree added: ${tree.asPath}")
        locationList.addAll(project.files(tree).map { file ->
            val srcDir = tree.srcDirs.first {
                file.path.startsWith(it.path + "/")
            }
            println("dep location for src dir: $srcDir is ${file.path}")
            Location.get(
                    base = srcDir.path,
                    path = file.path.substring(srcDir.path.length + 1)
            )
        })
    }
  }

  private fun resolveDependency(project: Project, path: String): Dependency {
    val parser = FileOrUriNotationConverter.parser()

    val converted = parser.parseNotation(path)

    if (converted is File) {
      println("file resolved: $converted for path: $path")
      val file = if (!converted.isAbsolute) File(project.projectDir, converted.path) else converted

      check(file.exists()) { "Invalid path string: \"$path\". Path does not exist." }

      return when {
        file.isDirectory -> {
          println("dir resolved: $path")
          project.dependencies.create(project.files(path))
        }
        file.isJar -> {
          println("jar resolved: $path")
          project.dependencies.create(project.files(file.path))
        }
        else -> throw IllegalArgumentException(
            """
            |Invalid path string: "$path".
            |For individual files, use the following syntax:
            |wire {
            |  sourcePath {
            |    srcDir 'dirPath'
            |    include 'relativePath'
            |  }
            |}
            """.trimMargin()
        )
      }
    } else if (converted is URI && isURL(converted)) {
      throw IllegalArgumentException(
          "Invalid path string: \"$path\". URL dependencies are not allowed."
      )
    } else {
      println("external deps: $path")
      // Assume it's a possible external dependency and let Gradle sort it out later.
      return project.dependencies.create(path)
    }
  }

  private fun isURL(uri: URI) =
      try {
        uri.toURL()
        true
      } catch (e: Exception) {
        false
      }

  fun debug(logger: Logger) {
    configuration2.dependencies.forEach { dep ->
      val srcDirs = ((dep as? FileCollectionDependency)?.files as? SourceDirectorySet)?.srcDirs
      val includes = dependencyToIncludes[dep.id] ?: listOf()
      logger.debug("dep: $dep -> $srcDirs")
      logger.debug("$name.files for dep: ${configuration2.files(dep)}")
      logger.debug("$name.includes for dep: $includes")
    }
  }

  val Dependency.id
          get() = "$group:$name:$version"

  fun toLocations(): List<Location> {
    println("new location list: $locationList")
    val locationConfig2 = configuration2.dependencies
            .flatMap { dep ->
              configuration2.files(dep)
                      .map { file ->
                        val includes = dependencyToIncludes[dep.id] ?: listOf()
                        println("dep location for jar include: $includes for path: ${dep.id}")
                        if (includes.isEmpty()) {
                          return listOf(Location.get(file.path))
                        }

                        return includes.map { include ->
                          Location.get(base = file.path, path = include)
                        }
                      }
            }
    println("new config : $locationConfig2")
    val locationConfig = configuration2.dependencies
            .flatMap { dep ->
              configuration2.files(dep)
                      .flatMap { file ->
                        file.toLocations(dep)
                      }
            }
      println("old config : $locationConfig")
      println("equal config : ${locationConfig.equals(locationConfig2.plus(locationList))}")
      return locationConfig2.plus(locationList)
  }

  private fun File.toLocations(dependency: Dependency): List<Location> {
    if (dependency is FileCollectionDependency && dependency.files is SourceDirectorySet) {
      val srcDir = (dependency.files as SourceDirectorySet).srcDirs.first {
        path.startsWith(it.path + "/")
      }
      println("dep location for src dir: $path")
      return listOf(Location.get(
          base = srcDir.path,
          path = path.substring(srcDir.path.length + 1)
      ))
    }

    val includes = dependencyToIncludes[dependency.id] ?: listOf()
    println("dep location for jar include: $includes for path: ${dependency.id}")
    if (includes.isEmpty()) {
      return listOf(Location.get(path))
    }

    return includes.map { include ->
      Location.get(base = path, path = include)
    }
  }

  private val File.isJar
    get() = path.endsWith(".jar")
}
