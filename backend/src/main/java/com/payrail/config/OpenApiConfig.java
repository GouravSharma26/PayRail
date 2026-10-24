package com.payrail.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayRail — Distributed Payment Processing & Ledger Simulator")
                        .version("1.0.0")
                        .description("""
                                ### Synthetic-Only Payment Simulator
                                **CRITICAL SAFETY BOUNDARY:** This platform is strictly an educational simulator.
                                It does NOT connect to real banks, payment gateways, card rails, or customer accounts.
                                All balances and transfers are synthetic test data.
                                """)
                        .contact(new Contact().name("PayRail Engineering Team"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("/").description("Default Server")
                ));
    }
}
