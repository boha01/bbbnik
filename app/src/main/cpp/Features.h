#pragma once
#include <atomic>
struct Vec3 { float x=0,y=0,z=0; };
struct Features {
    // PLAYER
    std::atomic<bool> esp{false}, skeleton{false}, showNpcs{true}, showVehicles{false};
    std::atomic<float> espDistance{500.f};
    std::atomic<int> espBoxColor{static_cast<int>(0xFFA855F7)};
    // COMBAT
    std::atomic<bool> godMode{false}, infiniteAmmo{false}, rapidFire{false}, noRecoil{false};
    // VEHICLE
    std::atomic<bool> vehicleSpeedOn{false}, instantBrake{false};
    std::atomic<float> vehicleSpeed{1.f};
    std::atomic<bool> teleportRequested{false};
    // VISUALS
    std::atomic<float> fov{60.f};
    std::atomic<bool> nightVision{false}, freeFly{false};
    // STATE from hooks
    std::atomic<bool> hooked{false};
    Vec3 playerPos; // written by Position hook, read via JNI
    Vec3 waypoint;  // TODO: fill from game's waypoint/marker system
    bool hasWaypoint = false;
};
inline Features F;
