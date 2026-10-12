package com.restaurantes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthTest {

    @Test
    void liveness() {
        given().when().get("/health/live").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void readinessIncluyeLaBaseDeDatos() {
        given().when().get("/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }
}
