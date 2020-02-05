/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.schema.internal

import com.squareup.wire.ProtoAdapter
import com.squareup.wire.schema.Schema
import java.util.ArrayDeque
import java.util.Locale
import java.util.Queue

@Suppress("NOTHING_TO_INLINE") // Aliasing to platform method.
internal actual inline fun Char.isDigit() = Character.isDigit(this)

@Suppress("NOTHING_TO_INLINE") // Aliasing to platform method.
internal actual inline fun String.toEnglishLowerCase() = toLowerCase(Locale.US)

actual typealias MutableQueue<T> = Queue<T>

internal actual fun <T : Any> mutableQueueOf(): MutableQueue<T> = ArrayDeque()

internal actual fun Schema.createProtoAdapter(
  typeName: String,
  includeUnknown: Boolean
): ProtoAdapter<Any> {
  val type = requireNotNull(getType(typeName)) { "unexpected type $typeName" }
  return SchemaProtoAdapterFactory(this, includeUnknown)[type.type]
}
