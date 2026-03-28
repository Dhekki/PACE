package org.acme.vehiclerouting.domain;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(scope = Passenger.class, generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Passenger {

    private String id;
    private String name;
    private Location pickupLocation;
    private Location dropoffLocation;
    private int demand;

    public Passenger() {
    }

    public Passenger(String id, String name, Location pickupLocation, Location dropoffLocation, int demand) {
        this.id = id;
        this.name = name;
        this.pickupLocation = pickupLocation;
        this.dropoffLocation = dropoffLocation;
        this.demand = demand;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Location getPickupLocation() { return pickupLocation; }
    public void setPickupLocation(Location pickupLocation) { this.pickupLocation = pickupLocation; }

    public Location getDropoffLocation() { return dropoffLocation; }
    public void setDropoffLocation(Location dropoffLocation) { this.dropoffLocation = dropoffLocation; }

    public int getDemand() { return demand; }
    public void setDemand(int demand) { this.demand = demand; }

    @Override
    public String toString() {
        return name;
    }
}
