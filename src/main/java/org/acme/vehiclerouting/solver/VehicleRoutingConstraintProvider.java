package org.acme.vehiclerouting.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.VisitType;
import org.acme.vehiclerouting.domain.Vehicle;

public class VehicleRoutingConstraintProvider implements ConstraintProvider {

    public static final String VEHICLE_CAPACITY = "vehicleCapacity";
    public static final String MAXIMIZE_VISITS_ASSIGNED = "maximizeVisitsAssigned";
    public static final String SERVICE_FINISHED_AFTER_MAX_END_TIME = "serviceFinishedAfterMaxEndTime";
    public static final String MINIMIZE_TRAVEL_TIME = "minimizeTravelTime";
    public static final String PICKUP_DELIVERY_SAME_VEHICLE = "pickupDeliverySameVehicle";
    public static final String PICKUP_BEFORE_DELIVERY = "pickupBeforeDelivery";
    public static final String PENALIZE_EMPTY_VANS = "penalizeEmptyVans";

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                vehicleCapacity(factory),
                serviceFinishedAfterMaxEndTime(factory),
                pickupDeliverySameVehicle(factory),
                pickupBeforeDelivery(factory),
                maximizeVisitsAssigned(factory),
                minimizeTravelTime(factory),
                penalizeEmptyVans(factory)
        };
    }

    protected Constraint pickupDeliverySameVehicle(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVisitType() == VisitType.PICKUP)
                .join(Visit.class,
                        Joiners.equal(Visit::getPassenger),
                        Joiners.filtering((pickup, delivery) -> delivery.getVisitType() == VisitType.DELIVERY))
                .filter((pickup, delivery) -> pickup.getVehicle() != delivery.getVehicle())
                .penalizeLong(HardMediumSoftLongScore.ONE_HARD, (pickup, delivery) -> 1L)
                .asConstraint(PICKUP_DELIVERY_SAME_VEHICLE);
    }

    protected Constraint pickupBeforeDelivery(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVisitType() == VisitType.PICKUP)
                .join(Visit.class,
                        Joiners.equal(Visit::getPassenger),
                        Joiners.equal(Visit::getVehicle),
                        Joiners.filtering((pickup, delivery) -> delivery.getVisitType() == VisitType.DELIVERY))
                .filter((pickup, delivery) -> pickup.getArrivalTime() != null && delivery.getArrivalTime() != null
                        && pickup.getArrivalTime().isAfter(delivery.getArrivalTime()))
                .penalizeLong(HardMediumSoftLongScore.ONE_HARD, (pickup, delivery) -> 1L)
                .asConstraint(PICKUP_BEFORE_DELIVERY);
    }

    protected Constraint vehicleCapacity(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null && visit.getVehicleLoad() != null)
                .filter(visit -> visit.getVehicleLoad() > visit.getVehicle().getCapacity())
                .penalizeLong(HardMediumSoftLongScore.ONE_HARD,
                        visit -> visit.getVehicleLoad() - visit.getVehicle().getCapacity())
                .asConstraint(VEHICLE_CAPACITY);
    }

    protected Constraint serviceFinishedAfterMaxEndTime(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(Visit::isServiceFinishedAfterMaxEndTime)
                .penalizeLong(HardMediumSoftLongScore.ONE_HARD,
                        Visit::getServiceFinishedDelayInMinutes)
                .asConstraint(SERVICE_FINISHED_AFTER_MAX_END_TIME);
    }

    protected Constraint maximizeVisitsAssigned(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(Visit.class)
                .filter(v -> v.getVehicle() == null)
                .penalizeLong(HardMediumSoftLongScore.ONE_MEDIUM, v-> v.getServiceDuration().toMinutes())
                .asConstraint(MAXIMIZE_VISITS_ASSIGNED);
    }

    protected Constraint minimizeTravelTime(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .penalizeLong(HardMediumSoftLongScore.ONE_SOFT,
                        Vehicle::getTotalDrivingTimeSeconds)
                .asConstraint(MINIMIZE_TRAVEL_TIME);
    }

    protected Constraint penalizeEmptyVans(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .filter(vehicle -> vehicle.getVisits().isEmpty())
                .penalizeLong(HardMediumSoftLongScore.ONE_SOFT, vehicle -> 1000000L)
                .asConstraint(PENALIZE_EMPTY_VANS);
    }
}
