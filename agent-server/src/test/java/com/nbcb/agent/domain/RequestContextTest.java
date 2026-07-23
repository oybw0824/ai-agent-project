package com.nbcb.agent.domain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 请求上下文并发隔离测试。 */
class RequestContextTest {

    @AfterEach
    void tearDown() {
        RequestContext.cleanupAll();
    }

    @Test
    @DisplayName("跨线程降级仅在活动请求唯一时生效")
    void shouldNotGuessContextWhenMultipleRequestsAreActive() {
        RequestContext first = RequestContext.begin(null);
        first.detachCurrentThread();
        assertThat(RequestContext.current()).isSameAs(first);

        RequestContext second = RequestContext.begin(null);
        second.detachCurrentThread();
        assertThat(RequestContext.current()).isNull();

        first.close();
        assertThat(RequestContext.current()).isSameAs(second);

        second.close();
        assertThat(RequestContext.current()).isNull();
    }
}
