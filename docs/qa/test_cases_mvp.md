# MVP Verification Test Cases

## Context
- **Stage**: MVP (Static JSON Data)
- **Goal**: Verify Backend API and Frontend Rendering match the `routine_schedule.md`.

## Test Cases

### TC-01: Backend API Health
- **Target**: `http://localhost:8080/api/mvp/routine`
- **Expected**: HTTP 200 OK, JSON response containing "week" array.
- **Criteria**: "Monday" block must contain "09:30 - 18:30" (Work).

### TC-02: Frontend Main Loading
- **Target**: `http://localhost:3000`
- **Expected**: "Smart Schedule SaaS MVP" header is visible. "Weekly Routine" title is visible.
- **Criteria**: No "Loading..." state stuck.

### TC-03: Timetable Data Rendering (Monday)
- **Target**: Monday Column
- **Expected**:
    1.  Block 1: "09:30 - 18:30" | "근무"
    2.  Block 2: "22:00 - 23:00" | "공부 (토익)"
- **Criteria**: Text must match exactly.

### TC-04: Visual Category Styling
- **Target**: Blocks
- **Expected**:
    -   "근무" (Work) should have Blue background.
    -   "공부" (Growth) should have Purple background.
- **Criteria**: Colors must be distinct.
