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
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
/**
 * Implementation of {@link ApplicationDAO} interface, leveraging {@link RedisTemplate} to do the
 * heavy lifting.
 *
 * @author Greg Turnquist
 */
class RedisApplicationDAO implements ApplicationDAO {

  RedisTemplate<String, Application> redisTemplate

  RedisConnectionFactory factory

  @Override
  Application findByName(String name) throws NotFoundException {
    findById(name)
  }

  @Override
  Collection<Application> search(Map<String, String> attributes) {
    ApplicationDAO.Searcher.search(all(), attributes)
  }

  @Override
  Application findById(String name) throws NotFoundException {
    def app = redisTemplate.opsForHash().get(bookkeepingKey(), name.toUpperCase())
    if (!app) {
      throw new NotFoundException("No application found by id ${name}")
    }
    app
  }

  @Override
  Collection<Application> all() {
    def applications = redisTemplate.opsForHash().scan(bookkeepingKey(), ScanOptions.scanOptions().match('*').build())
        .collect { it.value }

    if (!applications) {
      throw new NotFoundException("No applications available")
    }

    applications
  }

  @Override
  Application create(String id, Application application) {
    if (!application.createTs) {
      application.createTs = System.currentTimeMillis() as String
    }
    application.name = id.toUpperCase()

    redisTemplate.opsForHash().put(bookkeepingKey(), application.name, application)

    application
  }

  @Override
  void update(String id, Application application) {
    application.name = id
    application.updateTs = System.currentTimeMillis() as String

    create(id, application)
  }

  @Override
  void delete(String id) {
    redisTemplate.opsForHash().delete(bookkeepingKey(), id.toUpperCase())
  }

  @Override
  void bulkImport(Collection<Application> items) {
    items.each { create(it.id, it) }
  }

  @Override
  boolean isHealthy() {
    try {
      def conn = factory.connection
      conn.ping()
      conn.close()
      return true
    } catch (Exception e) {
      return false
    }
  }

  static String bookkeepingKey() {
    'com.netflix.spinnaker:front50:applications'
  }

}
