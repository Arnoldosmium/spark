/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.palantir.shuffle.async.util.streams;

import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.fs.CanSetReadahead;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Implementation of {@link SeekableInput} that reads bytes from a file from remote storage.
 */
public final class SeekableHadoopInput implements SeekableInput {

  private final Path path;
  private final FileSystem fs;

  public SeekableHadoopInput(Path path, FileSystem fs) {
    this.path = path;
    this.fs = fs;
  }

  @Override
  public InputStream open() throws IOException {
    return fs.open(path);
  }

  @Override
  public FSDataInputStream seekToAndOpen(long startPosition, long len) throws IOException {
    FSDataInputStream fsInput = this.seekToAndOpen(startPosition);
    InputStream wrapped = fsInput.getWrappedStream();
    if (wrapped instanceof CanSetReadahead) {
      fsInput.setReadahead(len);
    }
    return fsInput;
  }

  @Override
  public FSDataInputStream seekToAndOpen(long startPosition) throws IOException {
    FSDataInputStream fsInput = fs.open(path);
    fsInput.seek(startPosition);
    return fsInput;
  }
}
