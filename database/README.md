# SCH-02 PostgreSQL deployment

The application currently uses Hibernate schema updates. This migration is manual because the project has no migration runner. Rehearse the migration and the scheduling tests on a disposable PostgreSQL database before deployment.

1. Back up the target database and stop all application instances and other schedule writers. Verify the target schema contains `doctor_availability`, `appointment_slots`, and `uk_doctor_slot_time` and that the SCH-02 columns are absent.
2. Run `psql -X -v ON_ERROR_STOP=1 -f database/sch-02-availability-revisions.sql` with an approved connection to the target database and schema. Apply the script once, before starting the new application. The script runs in a transaction and can acquire table locks.
3. Verify that existing availability revisions are zero and all existing slots are retired with null provenance. Verify the existing slot and appointment counts, booking statuses, and slot references against the backup. Deploy the new version to every instance before accepting traffic.
4. Regenerate slots for current availability windows using the doctor or admin generation action or the authenticated `POST /api/appointment-slots/{availabilityId}/generate` endpoint. Generation only republishes eligible times. It may reissue an exact retired, unoccupied slot with the current revision to preserve the existing unique constraint. Existing bookings keep their original time, doctor, and slot. Cancelling a retired booking leaves its slot withdrawn until eligible generation explicitly republishes that interval.

Review any pre-existing overlapping confirmed bookings with scheduling staff. The migration preserves those bookings, and the new code rejects further bookings that overlap them. Keep the application stopped during rollback planning. An older application version would ignore retirement and could offer withdrawn slots.
