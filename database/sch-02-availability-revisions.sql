BEGIN;

ALTER TABLE doctor_availability
    ADD COLUMN revision bigint NOT NULL DEFAULT 0;

ALTER TABLE appointment_slots
    ADD COLUMN availability_id bigint,
    ADD COLUMN availability_revision bigint,
    ADD COLUMN retired boolean NOT NULL DEFAULT true,
    ADD CONSTRAINT fk_appointment_slot_availability
        FOREIGN KEY (availability_id) REFERENCES doctor_availability (id);

CREATE INDEX ix_appointment_slots_availability_revision
    ON appointment_slots (availability_id, availability_revision) WHERE NOT retired;

COMMIT;
