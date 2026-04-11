package org.acme.vehiclerouting.rest;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.constraint.ConstraintRef;
import ai.timefold.solver.core.api.solver.SolverStatus;

import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Passenger;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.VisitType;
import org.acme.vehiclerouting.domain.dto.ApplyRecommendationRequest;
import org.acme.vehiclerouting.domain.dto.RecommendationRequest;
import org.acme.vehiclerouting.domain.dto.VehicleRecommendation;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class VehicleRoutingPlanResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEMO_DATA_ID = "FEIRA_DE_SANTANA";

    @BeforeAll
    static void initializeJacksonParser() {
        OBJECT_MAPPER.findAndRegisterModules();
    }

    @Test
    void solveDemoDataUntilFeasible() {
        VehicleRoutePlan solution = solveDemoData();
        assertTrue(solution.getScore().isFeasible());
    }

    @Test
    void analyzeFetchAll() throws JsonProcessingException {
        VehicleRoutePlan solution = solveDemoData();
        assertTrue(solution.getScore().isFeasible());

        String analysisAsString = given()
                .contentType(ContentType.JSON)
                .body(solution)
                .expect().contentType(ContentType.JSON)
                .when()
                .put("/route-plans/analyze")
                .then()
                .extract()
                .asString();

        ScoreAnalysis<?> analysis = parseScoreAnalysis(analysisAsString);

        assertNotNull(analysis.score());
        ConstraintAnalysis<?> minimizeTravelTimeAnalysis =
                analysis.getConstraintAnalysis(ConstraintRef.of("org.acme.vehiclerouting.solver", "minimizeTravelTime"));
        assertNotNull(minimizeTravelTimeAnalysis);
        assertNotNull(minimizeTravelTimeAnalysis.matches());
        assertFalse(minimizeTravelTimeAnalysis.matches().isEmpty());
    }

    @Test
    void analyzeFetchShallow() throws JsonProcessingException {
        VehicleRoutePlan solution = solveDemoData();
        assertTrue(solution.getScore().isFeasible());

        String analysisAsString = given()
                .contentType(ContentType.JSON)
                .queryParam("fetchPolicy", "FETCH_SHALLOW")
                .body(solution)
                .expect().contentType(ContentType.JSON)
                .when()
                .put("/route-plans/analyze")
                .then()
                .extract()
                .asString();

        ScoreAnalysis<?> analysis = parseScoreAnalysis(analysisAsString);

        assertNotNull(analysis.score());
        ConstraintAnalysis<?> minimizeTravelTimeAnalysis =
                analysis.getConstraintAnalysis(ConstraintRef.of("org.acme.vehiclerouting.solver", "minimizeTravelTime"));
        assertNotNull(minimizeTravelTimeAnalysis);
        assertNull(minimizeTravelTimeAnalysis.matches());
    }

    private VehicleRoutePlan generateInitialSolution() {
        VehicleRoutePlan vehicleRoutePlan = given()
                .when().get("/demo-data/" + DEMO_DATA_ID)
                .then()
                .statusCode(200)
                .extract()
                .as(VehicleRoutePlan.class);

        String jobId = given()
                .contentType(ContentType.JSON)
                .body(vehicleRoutePlan)
                .expect().contentType(ContentType.TEXT)
                .when().post("/route-plans")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        await()
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(500L))
                .until(() -> SolverStatus.NOT_SOLVING.name().equals(
                        get("/route-plans/" + jobId + "/status")
                                .jsonPath().get("solverStatus")));

        return get("/route-plans/" + jobId).then().extract().as(VehicleRoutePlan.class);
    }

    private Visit generateNewVisit(VehicleRoutePlan solution) {
        Location pickupLocation = new Location(-12.2682, -38.9655);
        Location deliveryLocation = new Location(-12.1985, -38.9722);

        String newId = "p_test_integracao_" + System.currentTimeMillis();
        Passenger passenger = new Passenger(newId, "Passageiro de Teste (Integração)",
                pickupLocation, deliveryLocation, 1);

        solution.getPassengers().add(passenger);

        Visit pickupVisit = new Visit(newId + "_P",
                passenger, VisitType.PICKUP,
                LocalDateTime.now().plusDays(1).withHour(8).withMinute(0),
                LocalDateTime.now().plusDays(1).withHour(14).withMinute(0),
                Duration.ofMinutes(10));

        Visit deliveryVisit = new Visit(newId + "_D",
                passenger, VisitType.DELIVERY,
                LocalDateTime.now().plusDays(1).withHour(8).withMinute(0),
                LocalDateTime.now().plusDays(1).withHour(14).withMinute(0),
                Duration.ofMinutes(10));

        solution.getVisits().add(pickupVisit);
        solution.getVisits().add(deliveryVisit);

        return pickupVisit;
    }

    private List<Pair<VehicleRecommendation, ScoreAnalysis>> getRecommendations(VehicleRoutePlan solution, Visit newVisit) {
        RecommendationRequest request = new RecommendationRequest(solution, newVisit.getId());
        return parseRecommendedAssignmentList(given()
                .contentType(ContentType.JSON)
                .body(request)
                .expect().contentType(ContentType.JSON)
                .when()
                .post("/route-plans/recommendation")
                .then()
                .extract()
                .as(List.class));
    }

    private VehicleRoutePlan applyBestRecommendation(VehicleRoutePlan solution, Visit newVisit,
                                                     List<Pair<VehicleRecommendation, ScoreAnalysis>> recommendedAssignmentsList) {
        VehicleRecommendation recommendation = recommendedAssignmentsList.get(0).getLeft();
        ApplyRecommendationRequest applyRequest = new ApplyRecommendationRequest(solution, newVisit.getId(),
                recommendation.vehicleId(), recommendation.index());

        return given()
                .contentType(ContentType.JSON)
                .body(applyRequest)
                .expect().contentType(ContentType.JSON)
                .when()
                .post("/route-plans/recommendation/apply")
                .then()
                .extract()
                .as(VehicleRoutePlan.class);
    }

    @Test
    void recommendedAssignment() {
        VehicleRoutePlan solution = generateInitialSolution();
        assertNotNull(solution);
        assertEquals(SolverStatus.NOT_SOLVING, solution.getSolverStatus());

        Visit newVisit = generateNewVisit(solution);

        List<Pair<VehicleRecommendation, ScoreAnalysis>> recommendations = getRecommendations(solution, newVisit);
        assertNotNull(recommendations);
        assertEquals(5, recommendations.size());

        VehicleRoutePlan updatedSolution = applyBestRecommendation(solution, newVisit, recommendations);
        assertNotNull(updatedSolution);
        assertNotEquals(updatedSolution.getScore().toString(), solution.getScore().toString());
    }

    @Test
    void downloadPdfReportReturnsValidBinary() {
        VehicleRoutePlan vehicleRoutePlan = given()
                .when().get("/demo-data/" + DEMO_DATA_ID)
                .then()
                .statusCode(200)
                .extract()
                .as(VehicleRoutePlan.class);

        String jobId = given()
                .contentType(ContentType.JSON)
                .body(vehicleRoutePlan)
                .expect().contentType(ContentType.TEXT)
                .when().post("/route-plans")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        await()
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(500L))
                .until(() -> SolverStatus.NOT_SOLVING.name().equals(
                        get("/route-plans/" + jobId + "/status")
                                .jsonPath().get("solverStatus")));

        byte[] pdfBytes = given()
                .when().get("/route-plans/" + jobId + "/report")
                .then()
                .statusCode(200)
                .contentType("application/pdf")
                .header("Content-Disposition", "attachment; filename=\"Rota_" + jobId + ".pdf\"")
                .extract()
                .asByteArray();

        assertNotNull(pdfBytes, "O array de bytes do PDF não deveria ser nulo");
        assertTrue(pdfBytes.length > 100, "O PDF gerado parece estar vazio ou corrompido");
    }

    private VehicleRoutePlan solveDemoData() {
        VehicleRoutePlan vehicleRoutePlan = given()
                .when().get("/demo-data/" + DEMO_DATA_ID)
                .then()
                .statusCode(200)
                .extract()
                .as(VehicleRoutePlan.class);

        String jobId = given()
                .contentType(ContentType.JSON)
                .body(vehicleRoutePlan)
                .expect().contentType(ContentType.TEXT)
                .when().post("/route-plans")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        await()
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(500L))
                .until(() -> SolverStatus.NOT_SOLVING.name().equals(
                        get("/route-plans/" + jobId + "/status")
                                .jsonPath().get("solverStatus")));

        VehicleRoutePlan solution = get("/route-plans/" + jobId).then().extract().as(VehicleRoutePlan.class);
        assertEquals(SolverStatus.NOT_SOLVING, solution.getSolverStatus());
        assertNotNull(solution.getVehicles());
        assertNotNull(solution.getVisits());
        assertNotNull(solution.getVehicles().get(0).getVisits());
        return solution;
    }

    private ScoreAnalysis<?> parseScoreAnalysis(String analysis) throws JsonProcessingException {
        assertNotNull(analysis);
        return OBJECT_MAPPER.readValue(analysis, ScoreAnalysis.class);
    }

    private List<Pair<VehicleRecommendation, ScoreAnalysis>>
    parseRecommendedAssignmentList(List<Map<String, Object>> recommendedAssignmentMap) {
        assertNotNull(recommendedAssignmentMap);
        List<Pair<VehicleRecommendation, ScoreAnalysis>> recommendedAssignmentList = new ArrayList<>(recommendedAssignmentMap.size());
        recommendedAssignmentMap.forEach(record -> recommendedAssignmentList.add(Pair.of(
                OBJECT_MAPPER.convertValue(record.get("proposition"), VehicleRecommendation.class),
                OBJECT_MAPPER.convertValue(record.get("scoreDiff"), ScoreAnalysis.class))));
        return recommendedAssignmentList;
    }
}
