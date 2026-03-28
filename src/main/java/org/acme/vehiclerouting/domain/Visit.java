package org.acme.vehiclerouting.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;
import ai.timefold.solver.core.api.domain.variable.PreviousElementShadowVariable;
import ai.timefold.solver.core.api.domain.variable.ShadowSources;
import ai.timefold.solver.core.api.domain.variable.ShadowVariable;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(scope = Visit.class, generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@PlanningEntity
public class Visit implements LocationAware {

    @PlanningId
    private String id;
    private Passenger passenger;
    private VisitType visitType;
    private LocalDateTime minStartTime;
    private LocalDateTime maxEndTime;
    private Duration serviceDuration;

    @JsonIdentityReference(alwaysAsId = true)
    @InverseRelationShadowVariable(sourceVariableName = "visits")
    private Vehicle vehicle;

    @JsonIdentityReference(alwaysAsId = true)
    @PreviousElementShadowVariable(sourceVariableName = "visits")
    private Visit previousVisit;

    @ShadowVariable(supplierName = "arrivalTimeSupplier")
    private LocalDateTime arrivalTime;

    // NOVO: A variável de sombra que guarda a carga da van neste exato momento da rota
    @ShadowVariable(supplierName = "vehicleLoadSupplier")
    private Integer vehicleLoad;

    public Visit() {
    }

    public Visit(String id, Passenger passenger, VisitType visitType,
                 LocalDateTime minStartTime, LocalDateTime maxEndTime, Duration serviceDuration) {
        this.id = id;
        this.passenger = passenger;
        this.visitType = visitType;
        this.minStartTime = minStartTime;
        this.maxEndTime = maxEndTime;
        this.serviceDuration = serviceDuration;
    }

    public String getId() {
        return id;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getName() {
        return passenger != null ? passenger.getName() + " (" + visitType.name() + ")" : null;
    }

    @Override
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public Location getLocation() {
        if (passenger == null) return null;
        return visitType == VisitType.PICKUP ? passenger.getPickupLocation() : passenger.getDropoffLocation();
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public int getDemand() {
        if (passenger == null) return 0;
        return visitType == VisitType.PICKUP ? passenger.getDemand() : -passenger.getDemand();
    }

    public Passenger getPassenger() { return passenger; }
    public void setPassenger(Passenger passenger) { this.passenger = passenger; }

    public VisitType getVisitType() { return visitType; }
    public void setVisitType(VisitType visitType) { this.visitType = visitType; }

    public LocalDateTime getMinStartTime() { return minStartTime; }
    public void setMinStartTime(LocalDateTime minStartTime) { this.minStartTime = minStartTime; }

    public LocalDateTime getMaxEndTime() { return maxEndTime; }
    public void setMaxEndTime(LocalDateTime maxEndTime) { this.maxEndTime = maxEndTime; }

    public Duration getServiceDuration() { return serviceDuration; }
    public void setServiceDuration(Duration serviceDuration) { this.serviceDuration = serviceDuration; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public Visit getPreviousVisit() { return previousVisit; }
    public void setPreviousVisit(Visit previousVisit) { this.previousVisit = previousVisit; }

    public LocalDateTime getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(LocalDateTime arrivalTime) { this.arrivalTime = arrivalTime; }

    public Integer getVehicleLoad() { return vehicleLoad; }
    public void setVehicleLoad(Integer vehicleLoad) { this.vehicleLoad = vehicleLoad; }

    // ************************************************************************
    // Complex methods / Shadow Variable Suppliers
    // ************************************************************************

    @SuppressWarnings("unused")
    @ShadowSources({"vehicle", "previousVisit.arrivalTime"})
    public LocalDateTime arrivalTimeSupplier() {
        if (previousVisit == null && vehicle == null) {
            return null;
        }
        LocalDateTime departureTime = previousVisit == null ? vehicle.getDepartureTime() : previousVisit.getDepartureTime();
        return departureTime != null ? departureTime.plusSeconds(getDrivingTimeSecondsFromPreviousStandstill()) : null;
    }

    // NOVO: O motor matemático que calcula a ocupação contínua da Van
    @SuppressWarnings("unused")
    @ShadowSources({"vehicle", "previousVisit.vehicleLoad"})
    public Integer vehicleLoadSupplier() {
        if (vehicle == null) {
            return null;
        }
        // Se for a primeira parada, a carga anterior é 0. Senão, pega a carga da parada anterior.
        int previousLoad = (previousVisit == null || previousVisit.getVehicleLoad() == null) ? 0 : previousVisit.getVehicleLoad();

        // Carga atual = Carga Anterior + Demanda Atual (+1 no Pickup, -1 no Delivery)
        return previousLoad + getDemand();
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public LocalDateTime getDepartureTime() {
        if (arrivalTime == null) return null;
        return getStartServiceTime().plus(serviceDuration);
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public LocalDateTime getStartServiceTime() {
        if (arrivalTime == null) return null;
        return arrivalTime.isBefore(minStartTime) ? minStartTime : arrivalTime;
    }

    @JsonIgnore
    public boolean isServiceFinishedAfterMaxEndTime() {
        return arrivalTime != null && arrivalTime.plus(serviceDuration).isAfter(maxEndTime);
    }

    @JsonIgnore
    public long getServiceFinishedDelayInMinutes() {
        if (arrivalTime == null) return 0;
        return roundDurationToNextOrEqualMinutes(Duration.between(maxEndTime, arrivalTime.plus(serviceDuration)));
    }

    private static long roundDurationToNextOrEqualMinutes(Duration duration) {
        var remainder = duration.minus(duration.truncatedTo(ChronoUnit.MINUTES));
        var minutes = duration.toMinutes();
        if (remainder.equals(Duration.ZERO)) return minutes;
        return minutes + 1;
    }

    @JsonIgnore
    public long getDrivingTimeSecondsFromPreviousStandstill() {
        if (vehicle == null) {
            throw new IllegalStateException("This method must not be called when the shadow variables are not initialized yet.");
        }
        if (previousVisit == null) {
            return vehicle.getHomeLocation().getDrivingTimeTo(getLocation());
        }
        return previousVisit.getLocation().getDrivingTimeTo(getLocation());
    }

    @JsonProperty(value = "drivingTimeSecondsFromPreviousStandstill", access = JsonProperty.Access.READ_ONLY)
    public Long getDrivingTimeSecondsFromPreviousStandstillOrNull() {
        if (vehicle == null) return null;
        return getDrivingTimeSecondsFromPreviousStandstill();
    }

    @Override
    public String toString() {
        return id;
    }
}
