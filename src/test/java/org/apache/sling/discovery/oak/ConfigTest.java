/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.discovery.oak;

import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.Assert.assertNotNull;

public class ConfigTest {

    @Rule
    public final OsgiContext context = new OsgiContext();

    private Config config;

    @Before
    public void setUp() {
        Dictionary<String, Object> properties = new Hashtable<>();

        config = context.registerInjectActivateService(new Config(), properties);
    }

    @Test
    public void testIfServiceActive() {
        assertNotNull(config);
    }
}