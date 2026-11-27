package com.kisapilot.kisaagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class VehicleCatalog {
    static final class Vehicle {
        final String id, label;

        Vehicle(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    static final class Group {
        final String brand;
        final List<Vehicle> cars;

        Group(String brand, List<Vehicle> cars) {
            this.brand = brand;
            this.cars = Collections.unmodifiableList(cars);
        }
    }

    final List<Group> groups;

    private VehicleCatalog(List<Group> groups) {
        this.groups = Collections.unmodifiableList(groups);
    }

    static VehicleCatalog empty() {
        return new VehicleCatalog(Collections.emptyList());
    }

    static VehicleCatalog parse(JSONObject response) {
        List<Group> groups = new ArrayList<>();
        JSONArray categories = response.optJSONArray("categories");
        for (int i = 0; categories != null && i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            String brand = field(category, "brand");
            if (brand.isEmpty()) continue;
            List<Vehicle> cars = parseCars(category.optJSONArray("cars"));
            if (!cars.isEmpty()) groups.add(new Group(brand, cars));
        }
        return new VehicleCatalog(groups);
    }

    private static List<Vehicle> parseCars(JSONArray rows) {
        List<Vehicle> cars = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            String id = field(row, "id");
            String name = field(row, "name");
            if (id.isEmpty() || name.isEmpty()) continue;
            cars.add(new Vehicle(id, name));
            counts.merge(name, 1, Integer::sum);
        }
        for (int i = 0; i < cars.size(); i++) {
            Vehicle car = cars.get(i);
            if (counts.get(car.label) > 1) cars.set(i, new Vehicle(car.id, car.label + " · " + car.id));
        }
        return cars;
    }

    private static String field(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    String labelFor(String id) {
        String label = null;
        for (Group group : groups) {
            for (Vehicle car : group.cars) {
                if (!id.equals(car.id)) continue;
                if (label != null && !label.equals(car.label)) return id;
                label = car.label;
            }
        }
        return label == null ? id : label;
    }
}
