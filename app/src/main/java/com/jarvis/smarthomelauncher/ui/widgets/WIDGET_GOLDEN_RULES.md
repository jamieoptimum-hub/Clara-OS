Clara Smart Home
Widget Architecture Standard (CWAS)
Version: 1.0

Purpose
This document defines the mandatory architectural standards for all Clara Smart Home widgets.

These standards exist to ensure:

Consistency
Scalability
Maintainability
Production readiness
Minimal technical debt
True plug-and-play deployment
All future widgets must comply with these rules.

Failure to comply should be considered a build failure.

CLARA PRIME DIRECTIVE
Never implement placeholder functionality.

Never implement mock data.

Never implement fake APIs.

Never leave TODO integrations.

Every widget must be fully operational, database-backed, modular, self-registering, popup-enabled, and deployable by dropping its folder into the widgets directory.

When replacing a widget, completely remove the previous implementation including files, models, repositories, settings, navigation routes, dependencies and dead code.

Clara must never contain parallel widget implementations, legacy widget remnants, mock services or dormant code paths.

If a dependency does not exist, build the dependency first before building the widget.

RULE 1
Every Widget Must Be Self-Contained
A widget must be deployable by dropping it into the widgets folder.

Bad
Widget requires modifications to:

Dashboard
Navigation
MainActivity
Widget Manager
Good
Widget contains:

Manifest
Widget
Popup
Repository
Settings
Registration
Clara discovers and registers widgets automatically.

No manual registration.

No code modifications outside the widget folder.

RULE 2
Zero Dummy Data
Forbidden:

fakeData()

sampleData()
mockFixtures()
dummyWeather()

Every displayed value must come from:

Real API
Real Database
Real Device
Real Integration
Real User Configuration

If a data source does not exist:

Build the source first.

Never fake it.

RULE 3
No Placeholder APIs
TODO("Connect API Later")
FakeRepository
MockService
StubProvider

Widgets are not complete until connected to live production data.

RULE 4
Widget First Architecture

Widgets must fit Clara.

Clara must never be modified to fit an individual widget.

No special handling.

No custom startup paths.

No widget-specific frameworks.

Every widget follows the same architecture.

RULE 5
Every Widget Must Have Two Experiences

Every widget must include:

Dashboard Experience

Purpose:

Passive awareness.

Visible from 2-4 metres.

No interaction required.

Hub Experience

Purpose:

Management and interaction.

Opened by clicking the widget header.

Provides the full experience.

Example:

Entertainment Widget

Dashboard:

12 Upcoming Releases

Hub:

Full Entertainment Platform

RULE 6
Popup First Design

Clicking a widget header must open:

Popup Hub

Never:

Launch Activity
Full Page Navigation
Dashboard Replacement

The dashboard should remain visible behind the popup.

RULE 7
Repository First Data Flow

Forbidden:

Widget → API

Required:

Widget ↓ Repository ↓ Database ↓ API

Widgets never directly access APIs.

RULE 8
Database Backed Configuration

All user-configurable preferences must be stored.

Examples:

Favourite Teams
Favourite Shows
Favourite Stations
Saved Routes
Widget Preferences

No temporary state as a primary storage system.

RULE 9
Rebuild Means Rebuild

When a widget is rebuilt:

Mandatory:

✅ Delete old widget files

✅ Delete old repositories

✅ Delete old models

✅ Delete old settings

✅ Delete obsolete navigation

✅ Delete unused resources

✅ Delete dead code

Forbidden:

Keeping old implementations "just in case"

There must only ever be one active version.

RULE 10
No Legacy Dependencies

New widgets may not depend on:

Legacy Managers
Deprecated Services
Obsolete Helpers
Old Navigation Systems

New development must target current architecture only.

RULE 11
All Widgets Must Support Core States

Every widget must support:

Loading

Loading content...

Ready

Data displayed normally.

Empty

No content available.

Example:

"No Teams Selected"

Error

Something failed.

Example:

"Unable to retrieve data"

No blank screens allowed.

RULE 12
Manifest Driven Registration

Every widget must include a widget manifest.

Example:
{
"id": "football-hub",
"name": "Football Hub",
"description": "Track favourite teams",
"supportsPopup": true,
"supportsSettings": true,
"supportsNotifications": true
}

Widget behaviour should be discoverable through its manifest.

No hidden configuration.

RULE 13
Dashboard Readability Standard

Dashboard information must be visible from:

2–4 metres away.

If data cannot be easily read at that distance:

Move it into the popup.

Dashboard:

Awareness
Status
Summary

Popup:

Detail
Interaction
Management
RULE 14
Shared Clara Services

Widgets must reuse Clara services.

Examples:

Notifications
Settings
Popup Engine
Database Layer
Voice Assistant
Automation Engine
Widget Registry

Do not create widget-specific versions.

RULE 15
Future Compatibility

Widgets should expose provider interfaces.

Example:

Widget ↓ Data Provider ↓ Repository

This allows future integrations such as:

Gemini
OpenAI
Claude
Local LLMs

Without rewriting the widget.

RULE 16
Standard Widget Folder Structure

Every widget must follow:

widgets/
WidgetName/ Manifest/ Widget/ Popup/ Repository/ Settings/ Models/ Services/
All widget functionality must remain inside its own folder.

RULE 17
Production Ready Definition

A widget is only considered complete when:

✅ Widget UI implemented

✅ Popup implemented

✅ Live APIs connected

✅ Database integrated

✅ Settings implemented

✅ Error handling implemented

✅ Empty states implemented

✅ Notifications implemented

✅ Manifest implemented

✅ Auto-registration working

✅ Legacy code removed

✅ Documentation created

Only then may the widget status be marked:

PRODUCTION READY

RULE 18
Documentation Is Mandatory

Every widget must contain:

README.md

Including:

Purpose
Data Sources
APIs Used
Database Tables
User Configuration
Automation Support
Voice Commands

No undocumented widgets.

RULE 19
Household Operating System Mindset

Widgets are not independent apps.

Widgets are Clara modules.

Each widget should contribute data to Clara's ecosystem where appropriate.

Examples:

Football → Calendar Timeline

Entertainment → Calendar Timeline

Deliveries → Calendar Timeline

Commute → Leave By Calculations

Weather → Travel Warnings

Widgets should cooperate and enhance each other.

RULE 20
Build Real Functionality Only

Do not build UI for functionality that does not exist.

Forbidden:

Future buttons
Placeholder screens
Empty tabs
Mock dashboards

Every visible feature must function.

If functionality is not ready:

Do not expose it.

Final Principle

Clara Smart Home is a platform, not a collection of widgets.

Every widget must be:

Modular
Self-contained
Discoverable
Replaceable
Production-ready
Data-driven
Fully functional

No shortcuts.

No placeholders.

No dead code.

No technical debt.

Build it once. Build it properly.