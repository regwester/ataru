-- name: yesql-get-1_92-latest-application-key-form-and-hakukohde
SELECT key, form_id, hakukohde
FROM latest_applications;

-- name: yesql-get-1_92-form
SELECT *
FROM forms
WHERE id = :id;

-- name: yesql-insert-1_92-attachment-review!
INSERT INTO application_hakukohde_attachment_reviews (application_key, attachment_key, hakukohde, state)
VALUES (:application_key, :attachment_key, :hakukohde, :state)