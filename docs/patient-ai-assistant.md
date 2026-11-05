# Patient FAQ chatbot operations

The existing Patient Portal **AI Assistant** tab now provides a local FAQ chatbot. Spring Boot matches common wording and keywords against predefined answers in [FaqChatbot.java](../src/main/java/com/harsh/hospitalmanagement/ai/FaqChatbot.java). It does not generate answers, call an external inference service, or require a model, API token, or paid account. Unknown questions receive a limited-scope fallback.

The React conversation sidebar, history, new/delete actions, Enter/Shift+Enter input and loading/error states remain in place. The existing local-ID password/Google sessions, Axios client and session CSRF tokens are reused.

## Knowledge base and source maintenance

Answers must describe the current implementation. Update the knowledge base and its tests when one of the source flows changes. Do not add a hospital-specific fact without an approved source.

| Topic | Source of application facts |
| --- | --- |
| Portal navigation and patient profile completion | [PatientDashboard.jsx](../frontend/src/pages/PatientDashboard.jsx), [PatientService.java](../src/main/java/com/harsh/hospitalmanagement/service/PatientService.java) |
| Email/password login and Google sign-in | [LoginPage.jsx](../frontend/src/pages/LoginPage.jsx), [AuthController.java](../src/main/java/com/harsh/hospitalmanagement/controller/AuthController.java), [CustomOidcUserService.java](../src/main/java/com/harsh/hospitalmanagement/service/CustomOidcUserService.java) |
| Account creation and safe Google identity handling | [UserController.java](../src/main/java/com/harsh/hospitalmanagement/controller/UserController.java), [UserService.java](../src/main/java/com/harsh/hospitalmanagement/service/UserService.java), [App.jsx](../frontend/src/App.jsx) |
| Doctor selection and specialization filtering | [PatientDashboard.jsx](../frontend/src/pages/PatientDashboard.jsx) |
| Booking instructions and status meanings | [PatientDashboard.jsx](../frontend/src/pages/PatientDashboard.jsx), [AppointmentService.java](../src/main/java/com/harsh/hospitalmanagement/service/AppointmentService.java), [AppointmentStatus.java](../src/main/java/com/harsh/hospitalmanagement/enums/AppointmentStatus.java) |
| Cancelling a confirmed appointment | [PatientDashboard.jsx](../frontend/src/pages/PatientDashboard.jsx), [AppointmentService.java](../src/main/java/com/harsh/hospitalmanagement/service/AppointmentService.java) |

The current React app has no Sign Up, password-reset or reschedule screen. Password patient registration exists as a backend operation; Google sign-in can create a new patient account when identity checks permit it. Completing **My Profile** is a separate step. Do not tell patients to click controls that do not exist. Doctor selection is available through **Book Appointment**; the chatbot does not claim to provide a separate public discovery screen.

The predefined scope includes HMS usage, registration/login guidance, doctor/specialization selection, booking/cancellation instructions, and limited general informational questions. It does not know hospital opening hours, contact details, billing/refund rules, current appointment availability or an individual patient's appointments. Medical questions receive a clinician referral; recognized emergency wording receives emergency-services guidance. It supplies no diagnosis, prescription or treatment advice.

## History, privacy and compatibility

Chat history remains because the owned conversation tables and bounded retrieval are already implemented. `PatientService.getPatientByUserId` resolves the authenticated patient's identity only to establish conversation ownership and recheck the current role. A completed patient profile is still required by the existing `patient_id` foreign key. FAQ answer generation uses only the submitted question, with no appointment service calls, profile facts or earlier chat context.

Messages are saved locally as one USER/ASSISTANT pair in a transaction. Each send locks its owned conversation row before checking the UUID. The unique `(session_id, request_id, role)` constraint prevents duplicate stored roles. An identical committed request replays the saved pair; changed text with the same UUID receives 409. A persistence failure rolls back the whole turn. The frontend does not retry POST automatically and preserves a failed draft/UUID while mounted. Refreshing discards an unconfirmed draft, so inspect saved history before resending manually.

Existing history and SQL migrations are preserved. The old `include_appointments` column remains for schema compatibility; new messages write false. The obsolete `includeAppointments` request field is ignored, and there is no sharing checkbox or patient-context feature. Questions and responses remain stored as chat text until the owned conversation is deleted. Patients should avoid entering passwords, medical records or other unnecessary personal information. Production still needs retention, storage-quota, backup and access-control decisions for locally stored conversations.

Timestamps follow the existing `timestamp(6) without time zone` convention and microsecond precision. Use consistent server timezones. New sends retain the bounded process-local limit of six attempts per local user/minute. Completed retries do not consume another attempt. The limit resets on restart and is not shared across instances.

## API contract

All routes require a PATIENT session cookie. POST/DELETE also require `X-CSRF-TOKEN` from `GET /api/auth/csrf`. The existing Axios client supplies both. Patient identity is derived through `LocalAccount.require(authentication)`; no request accepts a trusted patient ID. Unknown or foreign conversation IDs return the same 404.

| Method | Path | Body or paging | Result |
| --- | --- | --- | --- |
| POST | `/api/patient/assistant/sessions` | `{"title":"Portal questions"}` or `{}` | 201 session DTO |
| GET | `/api/patient/assistant/sessions` | `?page=0` (30 per page) | Owned session DTO array, updated descending |
| GET | `/api/patient/assistant/sessions/{id}/messages` | Optional `?before={messageId}` | Latest 50 before cursor, ascending ID |
| POST | `/api/patient/assistant/sessions/{id}/messages` | Send body below | 200 with completed pair |
| DELETE | `/api/patient/assistant/sessions/{id}` | None | 204, conversation/messages removed |

```json
{"requestId":"f27db7f3-3de6-4f7c-952b-83bcf76cd271","content":"How do I book an appointment?"}
```

The response is `{"messages":[...]}` with exactly one USER and one ASSISTANT DTO. Each message has `id`, `requestId`, `role`, `content` and `createdAt`. Session DTOs have `id`, `title`, `createdAt` and `updatedAt`. Blank titles become `New conversation`; the title limit is 80 characters. Messages require a UUID and 1-2,000 nonblank characters.

Invalid input returns 400; missing authentication returns 401; role/CSRF violations return 403; missing conversations return 404. A busy conversation or reused UUID with different text returns 409. The attempt limit returns 429 and `Retry-After`. A full limiter returns a controlled 503. These are application errors, with no external provider dependency.

## Database deployment and startup

No schema change is needed to replace inference with FAQs. Existing installations with AI-01 already applied must not reapply it. If conversation tables are not installed, rehearse and apply [AI-01](../database/ai-01-patient-assistant.sql) once using the [database runbook](../database/README.md) after explicit database approval. No PostgreSQL migration is executed by the simplification tests.

For an approved, migrated development database, set the datasource explicitly and validate the schema. Do not point verification runs at `hospital_management`. JDBC does not automatically use psql's pgpass file; use a hidden prompt or your existing secret manager.

```powershell
Set-Location 'C:\Users\Harsh\OneDrive\Desktop\Downloads\hospital-management-system\hospital-management-system'
$targetDatabase = Read-Host 'Approved migrated database name (not hospital_management for tests)'
if ([string]::IsNullOrWhiteSpace($targetDatabase) -or $targetDatabase -eq 'hospital_management') { throw 'Select an isolated, approved target' }
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/$targetDatabase"
$env:SPRING_DATASOURCE_USERNAME = 'postgres'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.postgresql.Driver'
$dbSecret = Read-Host 'Database password' -AsSecureString
$env:SPRING_DATASOURCE_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
$dbSecret.Dispose()
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
.\mvnw.cmd spring-boot:run
```

In another PowerShell window:

```powershell
Set-Location 'C:\Users\Harsh\OneDrive\Desktop\Downloads\hospital-management-system\hospital-management-system\frontend'
npm run dev
```

Keep the existing OAuth configuration and Vite `/api` proxy. Log in as a patient, complete **My Profile** if needed and open **AI Assistant**. No additional chatbot environment variables are needed. After stopping the backend, remove the process database credential if it was set here:

```powershell
Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
```

## Verification commands

Backend tests use embedded H2. The FAQ has no network client to mock and no paid API usage. The existing project has no PostgreSQL integration-test suite. On this Windows environment, use the cached startup Mockito agent to avoid the previously observed dynamic-attachment failure without source or pom changes.

```powershell
Set-Location 'C:\Users\Harsh\OneDrive\Desktop\Downloads\hospital-management-system\hospital-management-system'
$agent = Join-Path $env:USERPROFILE '.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar'
if (-not (Test-Path -LiteralPath $agent)) { throw 'Install the project-managed Mockito dependency first' }
$env:SPRING_DATASOURCE_URL = 'jdbc:h2:mem:hospital_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.h2.Driver'
$env:SPRING_DATASOURCE_USERNAME = 'sa'
$env:SPRING_DATASOURCE_PASSWORD = ''
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'create-drop'
$env:SPRING_SQL_INIT_MODE = 'never'
.\mvnw.cmd "-DargLine=-javaagent:$agent" test
if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed' }
Set-Location .\frontend
npm test
if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed' }
npm run lint
if ($LASTEXITCODE -ne 0) { throw 'Lint failed' }
npm run build
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
```

Browser-check desktop/mobile layout, history after refresh, failed-delivery retries, deletion confirmation, Enter/Shift+Enter, and account expiry in the deployed session/proxy setup. Hook-level UI tests do not replace browser layout validation. Only approved facts should be added to the FAQ; unsupported wording may fall back instead of being understood.
