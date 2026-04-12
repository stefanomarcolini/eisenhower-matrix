package com.tm.bff.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoreApiClientTest {

    @Test
    void refreshJwt_returnsTokenFromCoreApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoreApiClient client = new CoreApiClient("http://core-api", builder);

        server.expect(requestTo("http://core-api/internal/auth/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "token": "refreshed-jwt"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.refreshJwt("00000000-0000-0000-0000-000000000099"))
                .isEqualTo("refreshed-jwt");
        server.verify();
    }

    @Test
    void refreshJwt_throwsWhenCoreApiOmitsToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoreApiClient client = new CoreApiClient("http://core-api", builder);

        server.expect(requestTo("http://core-api/internal/auth/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refreshJwt("00000000-0000-0000-0000-000000000099"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Core API returned a refresh response without a token");
        server.verify();
    }

    @Test
    void refreshJwt_throwsWhenCoreApiReturnsEmptyBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoreApiClient client = new CoreApiClient("http://core-api", builder);

        server.expect(requestTo("http://core-api/internal/auth/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refreshJwt("00000000-0000-0000-0000-000000000099"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Core API returned an empty response for /internal/auth/refresh");
        server.verify();
    }

    @Test
    void exchangeOidcToken_buildsFallbackEmailNameAndIssuerFromClaims() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoreApiClient client = new CoreApiClient("http://core-api", builder);

        OidcUser user = mock(OidcUser.class);
        when(user.getIssuer()).thenReturn(null);
        when(user.getSubject()).thenReturn("mock-subject");
        when(user.getEmail()).thenReturn(null);
        when(user.getFullName()).thenReturn(null);
        when(user.getClaims()).thenReturn(Map.of(
                "iss", "http://mock-oauth2:8080/default",
                "preferred_username", "mock.user@test.io"
        ));

        server.expect(requestTo("http://core-api/internal/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("\"iss\":\"http://mock-oauth2:8080/default\"");
                    assertThat(body).contains("\"sub\":\"mock-subject\"");
                    assertThat(body).contains("\"email\":\"mock.user@test.io\"");
                    assertThat(body).contains("\"name\":\"mock.user\"");
                })
                .andRespond(withSuccess("""
                        {
                          "token": "jwt-token",
                          "userId": "00000000-0000-0000-0000-000000000099",
                          "tenantId": "00000000-0000-0000-0000-000000000001",
                          "role": "STANDARD",
                          "mfaRequired": false
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.exchangeOidcToken(user, "00000000-0000-0000-0000-000000000001");

        assertThat(response.token()).isEqualTo("jwt-token");
        server.verify();
    }

    @Test
    void exchangeOidcToken_usesSubjectAsEmailWhenSubjectAlreadyContainsAtSymbol() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoreApiClient client = new CoreApiClient("http://core-api", builder);

        OidcUser user = mock(OidcUser.class);
        when(user.getIssuer()).thenReturn(null);
        when(user.getSubject()).thenReturn("oauth2user@test.io");
        when(user.getEmail()).thenReturn(null);
        when(user.getFullName()).thenReturn(null);
        when(user.getClaims()).thenReturn(Map.of(
                "iss", "http://mock-oauth2:8080/default"
        ));

        server.expect(requestTo("http://core-api/internal/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("\"email\":\"oauth2user@test.io\"");
                    assertThat(body).doesNotContain("\"email\":\"oauth2user@test.io@mock-oauth2.local\"");
                })
                .andRespond(withSuccess("""
                        {
                          "token": "jwt-token",
                          "userId": "00000000-0000-0000-0000-000000000099",
                          "tenantId": "00000000-0000-0000-0000-000000000001",
                          "role": "STANDARD",
                          "mfaRequired": false
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.exchangeOidcToken(user, "00000000-0000-0000-0000-000000000001");

        assertThat(response.token()).isEqualTo("jwt-token");
        server.verify();
    }

    @Test
    void exchangeOidcToken_throwsWhenProviderDoesNotSupplyAnyEmailLikeClaim() {
        RestClient.Builder builder = RestClient.builder();
        CoreApiClient client = new CoreApiClient("http://core-api", builder);

        OidcUser user = mock(OidcUser.class);
        when(user.getIssuer()).thenReturn(null);
        when(user.getSubject()).thenReturn("provider-subject-only");
        when(user.getEmail()).thenReturn(null);
        when(user.getFullName()).thenReturn("Mock User");
        when(user.getClaims()).thenReturn(Map.of(
                "iss", "http://mock-oauth2:8080/default",
                "preferred_username", "mock-user"
        ));

        assertThatThrownBy(() -> client.exchangeOidcToken(user, "00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did not supply an email claim");
    }
}


