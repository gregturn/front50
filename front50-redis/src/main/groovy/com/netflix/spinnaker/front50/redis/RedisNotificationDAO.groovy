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

import com.netflix.spinnaker.front50.model.notification.HierarchicalLevel
import com.netflix.spinnaker.front50.model.notification.Notification
import com.netflix.spinnaker.front50.model.notification.NotificationDAO
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.util.Assert

/**
 * @author Greg Turnquist
 */
class RedisNotificationDAO implements NotificationDAO {

  RedisTemplate<String, Notification> redisTemplate

  StringRedisTemplate stringRedisTemplate

  @Override
  Collection<Notification> all() {
    stringRedisTemplate.opsForSet()
        .members(bookkeepingKey())
        .collect { redisTemplate.opsForValue().get(it) }
  }

  @Override
  Notification getGlobal() {
    get(HierarchicalLevel.GLOBAL, Notification.GLOBAL_ID)
  }

  @Override
  Notification get(HierarchicalLevel level, String name) {
    return redisTemplate.opsForValue().get(key(name)) ?: [email: []] as Notification // an empty Notification is expected for applications that do not exist
  }

  @Override
  void saveGlobal(Notification notification) {
    save(HierarchicalLevel.GLOBAL, Notification.GLOBAL_ID, notification)
  }

  @Override
  void save(HierarchicalLevel level, String name, Notification notification) {
    redisTemplate.execute({ RedisOperations<String, Notification> operations ->
      operations.multi()

      notification.level = level
      notification.name = name

      def key = key(name)

      redisTemplate.opsForValue().set(key, notification)
      stringRedisTemplate.opsForSet().add(bookkeepingKey(), key)

      operations.exec()
    } as SessionCallback)
  }

  @Override
  void delete(HierarchicalLevel level, String name) {
    redisTemplate.execute({ RedisOperations<String, Notification> operations ->
      operations.multi()

      def key = key(name)

      stringRedisTemplate.opsForSet().remove(bookkeepingKey(), key)
      redisTemplate.delete(key)

      operations.exec()
    } as SessionCallback)
  }

  static String key(String id) {
    Assert.notNull(id, 'id can NOT be null!')
    "com.netflix.spinnaker:front50:notifications:key:${id}".toString()
  }

  static String bookkeepingKey() {
    'com.netflix.spinnaker:front50:notifications:keys'
  }

}
