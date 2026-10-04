package my.edu.ukm.ftsm.ecommerce.controller;

import my.edu.ukm.ftsm.ecommerce.config.CorsConfig;
import my.edu.ukm.ftsm.ecommerce.config.SecurityConfig;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillBuyResponse;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.security.JwtUtils;
import my.edu.ukm.ftsm.ecommerce.service.SeckillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of POST /api/seckill/{eventId}/buy (docs/phases/P2.md §6.10) with the real security chain. */
@WebMvcTest(SeckillController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtUtils.class})
class SeckillControllerTest {

    private static final long USER = 5L;
    private static final long EVENT = 7L;

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private SeckillService seckillService;

    @Test
    void acceptedIs202WithTrackingToken() throws Exception {
        when(seckillService.buy(USER, EVENT)).thenReturn(
                new SeckillBuyResponse("ACCEPTED", "3f2b8c1e-5d6a-4e7b-9c0d-1a2b3c4d5e6f", "Your order is being processed."));

        mvc.perform(post("/api/seckill/{id}/buy", EVENT).with(student()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"))
                .andExpect(jsonPath("$.trackingToken").value("3f2b8c1e-5d6a-4e7b-9c0d-1a2b3c4d5e6f"));
    }

    @Test
    void rejectionsAre409WithTheSameBody() throws Exception {
        for (String result : List.of("SOLD_OUT", "ALREADY_BOUGHT", "NOT_ACTIVE")) {
            when(seckillService.buy(USER, EVENT)).thenReturn(new SeckillBuyResponse(result, null, "msg " + result));

            mvc.perform(post("/api/seckill/{id}/buy", EVENT).with(student()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.result").value(result))
                    .andExpect(jsonPath("$.trackingToken").doesNotExist())
                    .andExpect(jsonPath("$.message").value("msg " + result));
        }
    }

    @Test
    void unavailableIs503() throws Exception {
        when(seckillService.buy(USER, EVENT)).thenReturn(new SeckillBuyResponse("UNAVAILABLE", null,
                "We could not confirm your order. Please check My Orders before trying again."));

        mvc.perform(post("/api/seckill/{id}/buy", EVENT).with(student()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.result").value("UNAVAILABLE"));
    }

    @Test
    void unauthenticatedIs403() throws Exception {
        mvc.perform(post("/api/seckill/{id}/buy", EVENT)).andExpect(status().isForbidden());
        verifyNoInteractions(seckillService);
    }

    private static RequestPostProcessor student() {
        AuthPrincipal principal = new AuthPrincipal(USER, "student0@siswa.ukm.edu.my", "STUDENT");
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
