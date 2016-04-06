/*
 * Copyright 2016 the original author or authors.
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
import com.netflix.spinnaker.front50.model.pipeline.Pipeline
import com.netflix.spinnaker.front50.model.pipeline.PipelineStrategyDAO
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.util.Assert

/**
 * @author Greg Turnquist
 */
class RedisPipelineStrategyDAO implements PipelineStrategyDAO {

  RedisTemplate<String, Pipeline> redisTemplate

  StringRedisTemplate stringRedisTemplate

  RedisConnectionFactory factory

  @Override
  String getPipelineId(String application, String pipelineName) {
    all().find { pipeline ->
      pipeline.name == pipelineName && pipeline.application == application
    }.id
  }

  @Override
  Collection<Pipeline> getPipelinesByApplication(String application) {
    all().findAll { pipeline ->
      pipeline.application == application
    }
  }

  @Override
  Pipeline findById(String id) throws NotFoundException {
    def results = redisTemplate.opsForValue().get(key(id))
    if (!results) {
      throw new NotFoundException("No pipeline found with id '${id}'");
    }
    results
  }

  @Override
  Collection<Pipeline> all() {
    stringRedisTemplate.opsForSet()
        .members(bookkeepingKey())
        .collect { redisTemplate.opsForValue().get(it) }
  }

  @Override
  Pipeline create(String id, Pipeline item) {
    redisTemplate.execute({ RedisOperations<String, Pipeline> operations ->
      operations.multi()

      item.id = id
      if (!item.id) {
        item.id = UUID.randomUUID().toString()
      }

      def key = key(item.id)

      redisTemplate.opsForValue().set(key, item)
      stringRedisTemplate.opsForSet().add(bookkeepingKey(), key)

      operations.exec()

      item
    } as SessionCallback)
  }

  @Override
  void update(String id, Pipeline item) {
    item.lastModified = System.currentTimeMillis()
    create(id, item)
  }

  @Override
  void delete(String id) {
    redisTemplate.execute({ RedisOperations<String, Pipeline> operations ->
      operations.multi()

      def key = key(id)

      stringRedisTemplate.opsForSet().remove(bookkeepingKey(), key)
      redisTemplate.delete(key)

      operations.exec()
    } as SessionCallback)
  }

  @Override
  void bulkImport(Collection<Pipeline> items) {
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

  static String key(String id) {
    Assert.notNull(id, 'id can NOT be null!')
    "com.netflix.spinnaker:front50:pipeline-strategies:key:${id}".toString()
  }

  static String bookkeepingKey() {
    'com.netflix.spinnaker:front50:pipeline-strategies:keys'
  }

}
