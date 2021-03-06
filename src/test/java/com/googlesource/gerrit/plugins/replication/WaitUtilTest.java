// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication;

import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.googlesource.gerrit.plugins.replication.WaitUtil.waitUntil;

import java.time.Duration;
import org.junit.Test;

public class WaitUtilTest {

  @Test
  public void shouldFailWhenConditionNotMetWithinTimeout() throws Exception {
    assertThrows(
        InterruptedException.class,
        () -> waitUntil(() -> returnTrue() == false, Duration.ofSeconds(1)));
  }

  @Test
  public void shouldNotFailWhenConditionIsMetWithinTimeout() throws Exception {
    waitUntil(() -> returnTrue() == true, Duration.ofSeconds(1));
  }

  private static boolean returnTrue() {
    return true;
  }
}
