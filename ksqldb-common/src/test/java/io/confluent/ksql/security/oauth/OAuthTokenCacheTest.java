/*
 * Copyright 2024 Confluent Inc.
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

package io.confluent.ksql.security.oauth;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.internals.secured.BasicOAuthBearerToken;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OAuthTokenCacheTest {

  private static final short cacheExpiryBufferSeconds = 1;
  private final OAuthTokenCache oauthTokenCache = new OAuthTokenCache(cacheExpiryBufferSeconds);

  private static final String tokenString1 = "token1";


  @Test
  public void shouldSetCurrentToken() {
    OAuthBearerToken token1 = new BasicOAuthBearerToken(tokenString1,
        Collections.emptySet(),
        100L,
        "random",
        0L);

    oauthTokenCache.setCurrentToken(token1);
    assertEquals(tokenString1, oauthTokenCache.getCurrentToken().value());
  }

  @Test
  public void shouldGetExpiredTokenIsWithNull() {
    oauthTokenCache.setCurrentToken(null);
    assertTrue(oauthTokenCache.isTokenExpired());
  }

  @Test
  public void shouldGetExpiredWithValidCache() throws InterruptedException {
    long lifespan = 2L;
    OAuthBearerToken token1 = new BasicOAuthBearerToken(tokenString1,
        Collections.emptySet(),
        Instant.now().plusSeconds(lifespan).toEpochMilli(),
        "random",
        Instant.now().toEpochMilli());
    oauthTokenCache.setCurrentToken(token1);
    assertFalse(oauthTokenCache.isTokenExpired());
    Thread.sleep(10);
    assertFalse(oauthTokenCache.isTokenExpired());
  }

  @Test
  public void shouldGetExpiredWithExpiredCache() throws InterruptedException {
    long lifespanSeconds = 2L;
    OAuthBearerToken token1 = new BasicOAuthBearerToken(tokenString1,
        Collections.emptySet(),
        Instant.now().plusSeconds(lifespanSeconds).toEpochMilli(),
        "random",
        Instant.now().toEpochMilli());
    oauthTokenCache.setCurrentToken(token1);
    //sleeping till cache get expired
    Thread.sleep(
        (long) Math.floor(1000 * lifespanSeconds * OAuthTokenCache.CACHE_EXPIRY_THRESHOLD));
    assertTrue(oauthTokenCache.isTokenExpired());
  }

  @Test
  public void shouldCalculateTokenExpiryTime() {
    //already expired token
    long tokenStartTimeMs = Instant.now().plusSeconds(-3).toEpochMilli();
    long tokenExpiryTimeMs = Instant.now().plusSeconds(-1).toEpochMilli();
    OAuthBearerToken token1 = new BasicOAuthBearerToken(tokenString1,
        Collections.emptySet(),
        tokenExpiryTimeMs,
        "random",
        tokenStartTimeMs);

    assertEquals(tokenExpiryTimeMs, oauthTokenCache.calculateTokenExpiryTime(token1));

    //cache expiry time before requested cacheExpiryBufferSeconds seconds
    short cacheExpiryBufferSeconds = 5;
    final OAuthTokenCache oauthTokenCache = new OAuthTokenCache(cacheExpiryBufferSeconds);
    long lifetimeSeconds = 60L;
    tokenStartTimeMs = Instant.now().toEpochMilli();
    tokenExpiryTimeMs = Instant.now().plusSeconds(lifetimeSeconds).toEpochMilli();
    token1 = new BasicOAuthBearerToken(tokenString1,
        Collections.emptySet(),
        tokenExpiryTimeMs,
        "random",
        tokenStartTimeMs);
    long expectedCacheExpiryTimeMs = tokenStartTimeMs
        + (long) (OAuthTokenCache.CACHE_EXPIRY_THRESHOLD * (tokenExpiryTimeMs - tokenStartTimeMs));
    assertEquals(expectedCacheExpiryTimeMs,
        oauthTokenCache.calculateTokenExpiryTime(token1));

    //cache expiry should honor cacheExpiryBufferSeconds seconds
    lifetimeSeconds = 20L;
    tokenStartTimeMs = Instant.now().toEpochMilli();
    tokenExpiryTimeMs = Instant.now().plusSeconds(lifetimeSeconds).toEpochMilli();
    token1 = new BasicOAuthBearerToken(tokenString1,
        Collections.emptySet(),
        tokenExpiryTimeMs,
        "random",
        tokenStartTimeMs);

    expectedCacheExpiryTimeMs = tokenExpiryTimeMs
        - TimeUnit.MILLISECONDS.convert(cacheExpiryBufferSeconds, TimeUnit.SECONDS);
    assertEquals(expectedCacheExpiryTimeMs,
        oauthTokenCache.calculateTokenExpiryTime(token1));

  }

}