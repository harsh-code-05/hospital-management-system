// Submitted drafts stay in memory across SPA sign-in and are scoped to the local user ID.
export function createFormDrafts() {
  let ownerId = null;
  const values = new Map();

  return {
    selectOwner(userId) {
      if (userId !== ownerId) {
        values.clear();
        ownerId = userId;
      }
    },
    get(userId, form) {
      return userId != null && userId === ownerId ? values.get(form) : undefined;
    },
    save(userId, form, draft) {
      if (userId != null && userId === ownerId) values.set(form, draft);
    },
    discard(userId, form, draft) {
      if (userId === ownerId && values.get(form) === draft) values.delete(form);
    },
  };
}
