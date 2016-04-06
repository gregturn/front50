/*
 * Copyright 2016 Pivotal, Inc.
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

package com.netflix.spinnaker.front50.redis

import com.netflix.spinnaker.front50.exception.NotFoundException
import com.netflix.spinnaker.front50.model.application.Application
import com.netflix.spinnaker.front50.model.application.ApplicationDAO
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.util.Assert

/**
 * Implementation of {@link ApplicationDAO} interface, leveraging {@link RedisTemplate} to do the
 * heavy lifting.
 *
 * @author Greg Turnquist
 */
class RedisApplicationDAO implements ApplicationDAO {

  RedisTemplate<String, Application> redisTemplate

  StringRedisTemplate stringRedisTemplate

  RedisConnectionFactory factory

  @Override
  Application findByName(String name) throws NotFoundException {
    def results = redisTemplate.opsForValue().get(key(name))
    if (!results) {
      throw new NotFoundException("No Application found by name of ${name}")
    }
    results
  }

  @Override
  Collection<Application> search(Map<String, String> attributes) {
    ApplicationDAO.Searcher.search(all(), attributes)
  }

  @Override
  Application findById(String name) throws NotFoundException {
    def app = redisTemplate.opsForValue().get(key(name))
    if (!app) {
      throw new NotFoundException("No application found by id ${name}")
    }
    app
  }

  @Override
  Collection<Application> all() {
    def applications = stringRedisTemplate.opsForSet()
        .members(bookkeepingKey())
        .collect { redisTemplate.opsForValue().get(it) }

    if (!applications) {
      throw new NotFoundException("No applications available")
    }

    applications
  }

  @Override
  Application create(String id, Application application) {
    redisTemplate.execute({ RedisOperations<String, Application> operations ->
      operations.multi()

      if (!application.createTs) {
        application.createTs = System.currentTimeMillis() as String
      }
      application.name = id.toUpperCase()

      def key = key(id)

      redisTemplate.opsForValue().set(key, application)
      stringRedisTemplate.opsForSet().add(bookkeepingKey(), key)

      operations.exec()

      application
    } as SessionCallback)
  }

  @Override
  void update(String id, Application application) {
    application.name = id
    application.updateTs = System.currentTimeMillis() as String

    create(id, application)
  }

  @Override
  void delete(String id) {
    redisTemplate.execute({ RedisOperations<String, Application> operations ->
      operations.multi()

      def key = key(id)

      stringRedisTemplate.opsForSet().remove(bookkeepingKey(), key)
      redisTemplate.delete(key)

      operations.exec()
    } as SessionCallback)
  }

  @Override
  void bulkImport(Collection<Application> items) {
    items.each { create(it.id, it) }
  }

  @Override
  boolean isHealthy() {
    try {
      def conn = factory.connection
      conn.close()
      return true
    } catch (Exception e) {
      return false
    }
  }

  static String key(String name) {
    Assert.notNull(name, 'name can NOT be null!')
    "com.netflix.spinnaker:front50:applications:key:${name.toUpperCase()}".toString()
  }

  static String bookkeepingKey() {
    'com.netflix.spinnaker:front50:applications:keys'
  }

}
