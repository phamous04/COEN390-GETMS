# Android Application Software Design Document

## Project Name

EMG & IMU Controlled Robotic Arm Mobile Application

---

# 1. Overview

## Purpose

The purpose of this Android application is to provide a user interface and control platform for a wearable EMG and IMU device powered by an ESP32.

The application will:

- Connect to the ESP32 via Bluetooth Low Energy (BLE)
- Receive EMG and IMU sensor data in real time
- Visualize arm movement through a 3D robotic arm model
- Manage user accounts and calibration profiles
- Store user-specific calibration settings in the cloud
- Eliminate the need for repeated calibration for returning users
- Display device status, battery information, and sensor readings

---

## Goals

### Primary Goals

- Real-time communication with wearable device
- Real-time visualization of arm movement
- User authentication and profile management
- Cloud-based calibration storage
- Intuitive Android user experience

### Secondary Goals

- Session history tracking
- User-specific settings
- Future robotic arm integration
- Future gesture recognition improvements

---

# 2. Technology Stack

## Mobile Application

### Development Environment

- Android Studio

### Programming Language

- Kotlin

### UI Framework

- Jetpack Compose

### Architecture Pattern

- MVVM (Model-View-ViewModel)

### Dependency Injection

- Hilt

### Asynchronous Programming

- Kotlin Coroutines
- Kotlin Flow

---

## Communication

### Device Communication

- Bluetooth Low Energy (BLE)

---

## Database and Backend

### Backend Platform

- Firebase

### Authentication

- Firebase Authentication

### Cloud Database

- Cloud Firestore

### Optional Storage

- Firebase Storage

---

## Local Storage

### User Preferences

- DataStore

---

## 3D Visualization

### Rendering Engine

- SceneView

### 3D Model Format

- GLB

### Modeling Software

- Blender

---

# 3. Application Architecture

```text
Presentation Layer
│
├── Screens
├── Components
├── Navigation
│
ViewModel Layer
│
├── UserViewModel
├── DeviceViewModel
├── CalibrationViewModel
├── RoboticArmViewModel
│
Repository Layer
│
├── Firebase Repository
├── BLE Repository
├── Settings Repository
│
Data Layer
│
├── Firebase
├── BLE Service
├── DataStore
└── Remote APIs
```

---

# 4. Core Features

## 4.1 User Authentication

### Description

Users must be able to create accounts and securely log into the application.

### Features

- User registration
- User login
- Forgot password
- Logout
- Persistent sessions

### Firebase Authentication Data

```text
Email
Password
User ID
Display Name
```

### Benefits

Calibration profiles remain linked to individual users.

Returning users can immediately use their saved settings.

---

## 4.2 Bluetooth Device Connection

### Description

The application communicates wirelessly with the ESP32 using BLE.

### Features

- Scan nearby devices
- Connect to ESP32
- Disconnect device
- Reconnect automatically
- Monitor signal strength
- Connection status indicator

### Data Received

#### EMG Sensor

```text
Raw EMG Value
Processed EMG Value
Muscle Activation Level
```

#### IMU Sensor

```text
Pitch
Roll
Yaw
Acceleration
Angular Velocity
```

#### Device Status

```text
Battery Percentage
Connection State
Device Information
```

---

## 4.3 Calibration Management

### Description

The application stores user-specific calibration settings.

A user only needs to calibrate once unless they choose to recalibrate.

---

### EMG Calibration

User performs:

#### Relaxed State

Record minimum EMG value.

#### Maximum Contraction

Record maximum EMG value.

Calculate:

```text
Activation Threshold
Sensitivity Curve
```

---

### IMU Calibration

Store:

```text
Pitch Offset
Roll Offset
Yaw Offset
```

---

### Stored Data

```text
User ID
EMG Minimum
EMG Maximum
Activation Threshold
Pitch Offset
Roll Offset
Yaw Offset
Calibration Date
```

---

## 4.4 3D Robotic Arm Visualization

### Description

The application contains a real-time 3D robotic arm that mirrors the user's movements.

### Objectives

- Visual feedback
- Demonstration platform
- Future robotic arm control

---

### Arm Components

```text
Base
Shoulder
Upper Arm
Forearm
Wrist
Gripper
```

### Sensor Mapping

```text
Pitch -> Shoulder Rotation

Roll -> Elbow Rotation

Yaw -> Wrist Rotation

EMG Activation -> Gripper Open/Close
```

### Features

- Real-time movement
- Camera controls
- Zoom and rotate view
- Joint angle display

---

## 4.5 Dashboard

### Description

Central screen displaying all device information.

### Information Displayed

```text
Current EMG Value
Pitch
Roll
Yaw
Battery Level
Connection Status
User Profile
```

### Optional Graphs

- Real-time EMG graph
- IMU orientation graph
- Session statistics

---

## 4.6 User Settings

### Functions

- Sensitivity adjustment
- Theme selection
- Recalibration
- BLE preferences
- Application preferences

---

## 4.7 Session History

### Description

Stores information about previous usage sessions.

### Information Stored

```text
Session Date
Session Duration
User Name
Calibration Version
Average EMG Activity
```

---

# 5. Database Design

## Firestore Collections

### Users Collection

```json
{
  "userId": "123",
  "name": "John Doe",
  "email": "john@email.com",
  "createdAt": "timestamp"
}
```

---

### Calibrations Collection

```json
{
  "userId": "123",
  "emgMin": 120,
  "emgMax": 3400,
  "emgThreshold": 2200,
  "pitchOffset": 1.4,
  "rollOffset": -0.9,
  "yawOffset": 0.1,
  "createdAt": "timestamp"
}
```

---

### Sessions Collection

```json
{
  "sessionId": "abc123",
  "userId": "123",
  "startTime": "timestamp",
  "endTime": "timestamp",
  "duration": 1200
}
```

---

# 6. Application Screens

## Splash Screen

### Purpose

Application initialization.

### Features

- App logo
- Firebase initialization
- Session validation

---

## Authentication Screen

### Features

- Login
- Registration
- Password reset

---

## Device Connection Screen

### Features

- Device scanning
- Pairing
- Connect/disconnect
- Signal strength monitoring

---

## Dashboard Screen

### Features

- Live sensor values
- Battery monitoring
- Device status
- User information

---

## Calibration Screen

### Features

- EMG calibration
- IMU calibration
- Save settings
- Load previous calibration

---

## Robotic Arm Screen

### Features

- 3D robotic arm
- Real-time movement tracking
- Camera controls
- Angle visualization

---

## Session History Screen

### Features

- Previous sessions
- Usage statistics

---

## Settings Screen

### Features

- User preferences
- BLE settings
- Theme selection
- Recalibration

---

# 7. Security Requirements

## User Security

- Firebase Authentication
- Secure password handling
- Session management

---

## Data Security

- Firestore security rules
- User-specific data isolation

---

## Device Security

- BLE device validation
- Connection authorization

---

# 8. Testing Plan

## Unit Testing

### Test Areas

- Authentication
- Calibration calculations
- Data processing
- BLE parsing

---

## Integration Testing

### Test Areas

- Firebase connectivity
- BLE communication
- Database synchronization
- Calibration persistence

---

## UI Testing

### Test Areas

- Screen navigation
- User workflows
- Error handling
- Responsiveness

---

## Performance Testing

### Metrics

- BLE latency
- UI frame rate
- Firebase response time
- Battery consumption

---

# 9. Future Enhancements

## Machine Learning

### Potential Features

- Gesture recognition
- Adaptive calibration
- Muscle fatigue detection

---

## Physical Robotic Arm Integration

Future versions may transmit commands directly to a physical robotic arm.

### Examples

```text
Move Shoulder
Move Elbow
Move Wrist
Open Gripper
Close Gripper
```

---

## Analytics Dashboard

Track:

- User activity
- Calibration history
- Device performance
- Sensor usage trends

---

# 10. Development Roadmap

## Phase 1 - Project Setup

- Android Studio project setup
- Firebase integration
- GitHub repository setup
- MVVM architecture setup

---

## Phase 2 - Authentication

- User registration
- Login
- Password reset
- Session persistence

---

## Phase 3 - BLE Communication

- Device scanning
- ESP32 connection
- Data reception
- Connection management

---

## Phase 4 - Calibration System

- EMG calibration
- IMU calibration
- Firestore integration
- Profile loading

---

## Phase 5 - Dashboard

- Sensor display
- Battery monitoring
- Device status monitoring

---

## Phase 6 - 3D Visualization

- Import robotic arm model
- Sensor-to-joint mapping
- Real-time animation

---

## Phase 7 - Session Management

- Session tracking
- Statistics
- Historical data

---

## Phase 8 - System Testing

- Functional testing
- Integration testing
- Performance testing
- User acceptance testing

---

# Deliverables

## Android Application

- User Authentication
- BLE Communication
- Firebase Integration
- Calibration Management
- Dashboard
- Session History
- 3D Robotic Arm Visualization
- Settings Management

## Firebase Backend

- Authentication
- User Profiles
- Calibration Storage
- Session Storage

## Documentation

- Software Design Document
- API Documentation
- Database Schema
- User Guide
- Installation Guide
