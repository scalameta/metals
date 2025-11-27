/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.turbine.parse;

class SavedToken {
  final Token token;
  final String value;
  final int position;

  SavedToken(Token token, String value, int position) {
    this.token = token;
    this.value = value;
    this.position = position;
  }

  @Override
  public String toString() {
    switch (token) {
      case IDENT:
      case CHAR_LITERAL:
      case LONG_LITERAL:
      case DOUBLE_LITERAL:
      case FLOAT_LITERAL:
      case INT_LITERAL:
      case STRING_LITERAL:
        return String.format("%s(%s)", token.name(), value);
      default:
        break;
    }
    return token.name();
  }
}
