package com.example.payment.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SampleController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .addFilters(new CorrelationIdFilter())
          .build();

  @Test
  void validationErrorListsFields() throws Exception {
    mvc.perform(
            post("/sample")
                .header(CorrelationIdFilter.HEADER, "trace-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":-1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.traceId").value("trace-1"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));
  }

  @Test
  void malformedBodyUsesEnvelope() throws Exception {
    mvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void unexpectedErrorHidesItsMessage() throws Exception {
    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(content().string(not(containsString("secret-detail"))));
  }

  record SampleRequest(@Positive long amount) {}

  @RestController
  static class SampleController {

    @PostMapping("/sample")
    void create(@Valid @RequestBody SampleRequest body) {}

    @GetMapping("/boom")
    void boom() {
      throw new IllegalStateException("secret-detail");
    }
  }
}
