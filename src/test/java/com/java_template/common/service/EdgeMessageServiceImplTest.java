package com.java_template.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaAccessDeniedException;
import com.java_template.common.exception.CyodaCalloutEndedException;
import com.java_template.common.exception.CyodaHttpException;
import com.java_template.common.exception.CyodaRetryableException;
import com.java_template.common.util.HttpUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EdgeMessageService surfaces the typed Cyoda exceptions unchanged (spec §4.4), and maps a REST 404 (a
 * {@link CyodaHttpException} with status 404, since HttpUtils throws on every 4xx) to its documented
 * {@code null}/{@code false}.
 */
class EdgeMessageServiceImplTest {

    private final HttpUtils http = mock(HttpUtils.class);
    private final ObjectMapper om = CyodaObjectMapper.standalone().protocol();
    private EdgeMessageServiceImpl service;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.setCyodaApiUrl("http://cyoda.test/api");
        service = new EdgeMessageServiceImpl(http, new CyodaCallContexts(config), CyodaObjectMapper.standalone(), config);
    }

    private static <T> CompletableFuture<T> failed(RuntimeException e) {
        // as HttpUtils fails it: inside a dependent stage, so join() wraps it in a CompletionException
        return CompletableFuture.<T>completedFuture(null).thenApply(x -> {
            throw e;
        });
    }

    private static CyodaHttpException notFound() {
        return new CyodaHttpException(404, "HTTP_404", "message not found", false);
    }

    // ---- 404 gives the documented null / false ----

    @Test
    void getMessageByIdReturnsNullOnA404() {
        when(http.sendGetRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(notFound()));

        assertThat(service.getMessageById(id)).isNull();
    }

    @Test
    void getMessageContentReturnsNullOnA404() throws Exception {
        when(http.sendGetRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(notFound()));

        assertThat(service.getMessageContent(id)).isNull();
    }

    @Test
    void deleteMessageReturnsFalseOnA404() {
        when(http.sendDeleteRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(notFound()));

        assertThat(service.deleteMessage(id)).isFalse();
    }

    // ---- typed exceptions propagate unchanged ----

    @Test
    void anEndedCalloutPropagatesUnchangedFromEveryMethod() {
        CyodaCalloutEndedException ended = new CyodaCalloutEndedException("CALLOUT_SUPERSEDED", "superseded");
        when(http.sendGetRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(ended));
        when(http.sendPostRequest(any(CyodaCallContext.class), anyString(), anyString(), any())).thenReturn(failed(ended));
        when(http.sendDeleteRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(ended));

        assertThatThrownBy(() -> service.getMessageById(id)).isSameAs(ended);
        assertThatThrownBy(() -> service.getMessageContent(id)).isSameAs(ended);
        assertThatThrownBy(() -> service.createMessage("subject", om.createObjectNode(), null)).isSameAs(ended);
        assertThatThrownBy(() -> service.deleteMessage(id)).isSameAs(ended);
    }

    @Test
    void aTransactionNotFound404IsAnEndedCalloutNotAMissingMessage() {
        // TRANSACTION_NOT_FOUND is a 404 too, but CyodaErrors maps it to CyodaCalloutEndedException
        CyodaCalloutEndedException ended = new CyodaCalloutEndedException("TRANSACTION_NOT_FOUND", "gone");
        when(http.sendGetRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(ended));

        assertThatThrownBy(() -> service.getMessageById(id)).isSameAs(ended);
    }

    @Test
    void anotherErrorPropagatesAsItsTypedException() {
        CyodaHttpException serverError = new CyodaHttpException(500, "INTERNAL", "boom", false);
        CyodaRetryableException retryable = new CyodaRetryableException("TOO_MANY_JOINED_REQUESTS", "full");
        CyodaAccessDeniedException denied = new CyodaAccessDeniedException("forbidden");
        when(http.sendGetRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(serverError));
        when(http.sendPostRequest(any(CyodaCallContext.class), anyString(), anyString(), any())).thenReturn(failed(retryable));
        when(http.sendDeleteRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(failed(denied));

        assertThatThrownBy(() -> service.getMessageById(id)).isSameAs(serverError);
        assertThatThrownBy(() -> service.createMessage("subject", om.createObjectNode(), null)).isSameAs(retryable);
        assertThatThrownBy(() -> service.deleteMessage(id)).isSameAs(denied);
    }

    @Test
    void aNon404HttpErrorOnCreateIsNotMistakenForNotFound() {
        CyodaHttpException notFoundOnCreate = notFound();
        when(http.sendPostRequest(any(CyodaCallContext.class), anyString(), anyString(), any())).thenReturn(failed(notFoundOnCreate));

        // createMessage has no "not found" result: a 404 there is a failure like any other
        assertThatThrownBy(() -> service.createMessage("subject", om.createObjectNode(), null)).isSameAs(notFoundOnCreate);
    }

    // ---- success paths ----

    @Test
    void successfulCallsReturnTheirResults() throws Exception {
        UUID created = UUID.randomUUID();
        ObjectNode get = om.createObjectNode().put("status", 200);
        get.putObject("json").put("content", "{\"a\":1}");
        ObjectNode post = om.createObjectNode().put("status", 200);
        post.putArray("json").addObject().put("success", true).putArray("entityIds").add(created.toString());
        ObjectNode delete = om.createObjectNode().put("status", 200);
        when(http.sendGetRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(get));
        when(http.sendPostRequest(any(CyodaCallContext.class), anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(post));
        when(http.sendDeleteRequest(any(CyodaCallContext.class), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(delete));

        assertThat(service.getMessageContent(id).get("a").asInt()).isEqualTo(1);
        assertThat(service.createMessage("subject", om.createObjectNode(), null)).isEqualTo(created);
        assertThat(service.deleteMessage(id)).isTrue();
    }
}
