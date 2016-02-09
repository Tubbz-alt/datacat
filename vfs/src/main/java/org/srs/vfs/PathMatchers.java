/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This is taken from Google's JimFS implementation.
 * It was adapted in 2014 by Brian Van Klaveren for datacat.
 */
package org.srs.vfs;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link PathMatcher} factory for any file system.
 *
 * @author Colin Decker
 */
public final class PathMatchers {

  private PathMatchers() {}

  /**
   * Gets a {@link PathMatcher} for the given syntax and pattern as specified by
   * {@link FileSystem#getPathMatcher}. The {@code separators} string contains the path name
   * element separators (one character each) recognized by the file system. For a glob-syntax path
   * matcher, any of the given separators will be recognized as a separator in the pattern, and any
   * of them will be matched as a separator when checking a path.
   */
  // TODO(cgdecker): Should I be just canonicalizing separators rather than matching any separator?
  // Perhaps so, assuming Path always canonicalizes its separators
  public static PathMatcher getPathMatcher(String syntaxAndPattern, String separators) {
    int syntaxSeparator = syntaxAndPattern.indexOf(':');
    if(syntaxSeparator <= 0) {
        throw new IllegalArgumentException( String.format("Must be of the form 'syntax:pattern': %s",syntaxAndPattern));
    }
    String syntax = syntaxAndPattern.substring(0, syntaxSeparator).toLowerCase();
    String pattern = syntaxAndPattern.substring(syntaxSeparator + 1);

    switch (syntax) {
      case "glob":
        pattern = GlobToRegex.toRegex(pattern, separators);
        // fall through
      case "regex":
        return fromRegex(pattern);
      default:
        throw new UnsupportedOperationException("Invalid syntax: " + syntaxAndPattern);
    }
  }

  private static PathMatcher fromRegex(String regex) {
    return new RegexPathMatcher(Pattern.compile(regex));
  }

  /**
   * {@code PathMatcher} that matches the {@code toString()} form of a {@code Path} against a regex
   * {@code Pattern}.
   */
  //@VisibleForTesting
  static final class RegexPathMatcher implements PathMatcher {

    private final Pattern pattern;

    private RegexPathMatcher(Pattern pattern) {
      Objects.requireNonNull(pattern);
      this.pattern = pattern;
    }

    @Override
    public boolean matches(Path path) {
      return pattern.matcher(path.toString()).matches();
    }

    @Override
    public String toString() {
      return pattern.pattern();
    }
  }
}