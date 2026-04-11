package org.acme.vehiclerouting.solver;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;

import jakarta.inject.Inject;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;

import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Passenger;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.VisitType;
import org.acme.vehiclerouting.domain.geo.HaversineDrivingTimeCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class VehicleRoutingConstraintProviderTest {

    private static final Location LOC_CENTRO = new Location(-12.2682, -38.9655);
    private static final Location LOC_UEFS = new Location(-12.1985, -38.9722);
    private static final Location LOC_BOULEVARD = new Location(-12.2544, -38.9472);

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);
    private static final LocalDateTime TOMORROW_07_00 = LocalDateTime.of(TOMORROW, LocalTime.of(7, 0));
    private static final LocalDateTime TOMORROW_08_00 = LocalDateTime.of(TOMORROW, LocalTime.of(8, 0));
    private static final LocalDateTime TOMORROW_09_00 = LocalDateTime.of(TOMORROW, LocalTime.of(9, 0));
    private static final LocalDateTime TOMORROW_10_00 = LocalDateTime.of(TOMORROW, LocalTime.of(10, 0));

    @Inject
    ConstraintVerifier<VehicleRoutingConstraintProvider, VehicleRoutePlan> constraintVerifier;

    @BeforeAll
    static void initDrivingTimeMaps() {
        HaversineDrivingTimeCalculator.getInstance().initDrivingTimeMaps(Arrays.asList(LOC_CENTRO, LOC_UEFS, LOC_BOULEVARD));
    }

    @Test
    void vehicleCapacityUnpenalized() {
        Vehicle vehicleA = new Vehicle("1", 2, LOC_CENTRO, TOMORROW_07_00);
        Passenger p1 = new Passenger("p1", "Murilo", LOC_CENTRO, LOC_UEFS, 1);
        Visit pickup1 = new Visit("2", p1, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));

        pickup1.setVehicleLoad(1);
        pickup1.setVehicle(vehicleA);

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::vehicleCapacity)
                .given(vehicleA, pickup1)
                .penalizesBy(0);
    }

    @Test
    void vehicleCapacityPenalized() {
        Vehicle vehicleA = new Vehicle("1", 1, LOC_CENTRO, TOMORROW_07_00);

        Passenger p1 = new Passenger("p1", "Murilo", LOC_CENTRO, LOC_UEFS, 1);
        Visit pickup1 = new Visit("2", p1, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));
        pickup1.setVehicleLoad(1);
        pickup1.setVehicle(vehicleA);

        Passenger p2 = new Passenger("p2", "Victor", LOC_BOULEVARD, LOC_UEFS, 1);
        Visit pickup2 = new Visit("3", p2, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));
        pickup2.setVehicleLoad(2);
        pickup2.setVehicle(vehicleA);

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::vehicleCapacity)
                .given(vehicleA, pickup1, pickup2)
                .penalizesBy(1);
    }

    @Test
    void pickupDeliverySameVehiclePenalized() {
        Vehicle vehicleA = new Vehicle("1", 2, LOC_CENTRO, TOMORROW_07_00);
        Vehicle vehicleB = new Vehicle("2", 2, LOC_BOULEVARD, TOMORROW_07_00);

        Passenger p1 = new Passenger("p1", "Passageiro Teste", LOC_CENTRO, LOC_UEFS, 1);

        Visit pickup = new Visit("2", p1, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));
        pickup.setVehicle(vehicleA);

        Visit delivery = new Visit("3", p1, VisitType.DELIVERY, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));
        delivery.setVehicle(vehicleB);

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::pickupDeliverySameVehicle)
                .given(pickup, delivery)
                .penalizesBy(1L);
    }

    @Test
    void pickupBeforeDeliveryPenalized() {
        Vehicle vehicleA = new Vehicle("1", 2, LOC_CENTRO, TOMORROW_07_00);
        Passenger p1 = new Passenger("p1", "Passageiro Teste", LOC_CENTRO, LOC_UEFS, 1);

        Visit pickup = new Visit("2", p1, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));
        pickup.setVehicle(vehicleA);
        pickup.setArrivalTime(TOMORROW_10_00);

        Visit delivery = new Visit("3", p1, VisitType.DELIVERY, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));
        delivery.setVehicle(vehicleA);
        delivery.setArrivalTime(TOMORROW_09_00);

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::pickupBeforeDelivery)
                .given(pickup, delivery)
                .penalizesBy(1L);
    }

    @Test
    void penalizeEmptyVans() {
        Vehicle emptyVan = new Vehicle("1", 2, LOC_CENTRO, TOMORROW_07_00);

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::penalizeEmptyVans)
                .given(emptyVan)
                .penalizesBy(1_000_000L);
    }

    @Test
    void unassignedPenalizedByDuration() {
        Passenger p1 = new Passenger("p1", "Passageiro Teste", LOC_CENTRO, LOC_UEFS, 1);
        Visit visit = new Visit("2", p1, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(30L));

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::maximizeVisitsAssigned)
                .given(visit)
                .penalizesBy(30L);
    }

    @Test
    void totalDrivingTime() {
        Vehicle vehicleA = new Vehicle("1", 100, LOC_CENTRO, TOMORROW_07_00);
        Passenger p1 = new Passenger("p1", "Passageiro Teste", LOC_UEFS, LOC_BOULEVARD, 1);

        Visit visit1 = new Visit("2", p1, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));
        Visit visit2 = new Visit("3", p1, VisitType.DELIVERY, TOMORROW_08_00, TOMORROW_10_00, Duration.ofMinutes(10L));

        connect(vehicleA, visit1, visit2);

        long expectedDrivingTime = vehicleA.getTotalDrivingTimeSeconds();

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::minimizeTravelTime)
                .given(vehicleA, visit1, visit2)
                .penalizesBy(expectedDrivingTime);
    }

    @Test
    void serviceFinishedAfterMaxEndTime() {
        LocalDateTime tomorrow_08_00_01 = LocalDateTime.of(TOMORROW, LocalTime.of(8, 0, 1));
        LocalDateTime tomorrow_08_40 = LocalDateTime.of(TOMORROW, LocalTime.of(8, 40));
        LocalDateTime tomorrow_10_30 = LocalDateTime.of(TOMORROW, LocalTime.of(10, 30));
        LocalDateTime tomorrow_18_00 = LocalDateTime.of(TOMORROW, LocalTime.of(18, 0));

        Passenger p1 = new Passenger("p1", "Passageiro Teste 1", LOC_CENTRO, LOC_UEFS, 1);
        Visit visit1 = new Visit("2", p1, VisitType.PICKUP, TOMORROW_08_00, tomorrow_18_00, Duration.ofMinutes(60L));
        visit1.setArrivalTime(tomorrow_08_40);

        Passenger p2 = new Passenger("p2", "Passageiro Teste 2", LOC_UEFS, LOC_CENTRO, 1);
        Visit visit2 = new Visit("3", p2, VisitType.PICKUP, TOMORROW_08_00, TOMORROW_09_00, Duration.ofMinutes(60L));
        visit2.setArrivalTime(tomorrow_10_30);

        Vehicle vehicleA = new Vehicle("1", 2, LOC_CENTRO, TOMORROW_07_00);
        connect(vehicleA, visit1, visit2);

        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::serviceFinishedAfterMaxEndTime)
                .given(vehicleA, visit1, visit2)
                .penalizesBy(150L);

        visit2.setArrivalTime(tomorrow_08_00_01);
        constraintVerifier.verifyThat(VehicleRoutingConstraintProvider::serviceFinishedAfterMaxEndTime)
                .given(vehicleA, visit1, visit2)
                .penalizesBy(1L);
    }

    static void connect(Vehicle vehicle, Visit... visits) {
        vehicle.setVisits(Arrays.asList(visits));
        for (int i = 0; i < visits.length; i++) {
            Visit visit = visits[i];
            visit.setVehicle(vehicle);
            if (i > 0) {
                visit.setPreviousVisit(visits[i - 1]);
            }
        }
    }
}
