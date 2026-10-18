import { describe, expect, it, vi } from 'vitest';

import {
  buildEditPayload,
  createSaveState,
  runEditSave,
  type EditPayload,
  type EditSaveApi
} from './editSave';
import { flushThenAct } from './flushThenAct';
import { TicketApiError, type CreatedTicket, type TicketExtraction } from '../api/tickets';

/**
 * Regression cover for the detail screen's save lifecycle.
 *
 * <p>These are the cases the component could not test on its own: the
 * screen is a shadow-DOM custom element jsdom does not mount, so the
 * orchestration lives in {@link runEditSave} precisely to be reachable
 * from here. Every async case uses a deferred promise rather than a
 * timer — no `sleep`, no flaky ordering.
 */

const TICKET: CreatedTicket = {
  id: 1,
  title: 'Weekly shop',
  description: 'edited by hand',
  status: 'OPEN',
  createdAt: '2026-07-03T17:00:00Z',
  updatedAt: '2026-07-03T17:00:00Z',
  contentType: 'image/png',
  fileName: 'receipt.png',
  sizeBytes: 2048,
  errorMessage: null,
  attempts: 0,
  ocrText: null
};

const EXTRACTION: TicketExtraction = {
  ticketId: TICKET.id,
  merchant: 'Mercadona',
  purchaseDate: '2026-07-03',
  category: 'food',
  products: [{ name: 'Bread', quantity: 2, unit: 'unit', pricePerUnit: 1.2, lineTotal: 2.4 }],
  totalAmount: 2.4,
  currency: 'EUR',
  model: 'gpt-4o-mini',
  extractedAt: '2026-07-03T17:05:00Z'
};

/** A promise whose settlement the test controls. */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (err: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (err: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Wiring a run with mutable local state, mirroring the component. */
function harness(options: {
  applyEdit?: EditSaveApi['applyEdit'];
  getExtraction?: EditSaveApi['getExtraction'];
  ticket?: CreatedTicket | null;
  extraction?: TicketExtraction | null;
  state?: ReturnType<typeof createSaveState>;
}) {
  const state = options.state ?? createSaveState();
  let ticket = options.ticket === undefined ? TICKET : options.ticket;
  let extraction = options.extraction === undefined ? EXTRACTION : options.extraction;
  const onCommitted = vi.fn();
  const onSaveFailed = vi.fn();
  const onUnstable = vi.fn();

  /** Mutate local state the way an input handler does. */
  function edit(fn: () => void): void {
    fn();
  }
  function markMetadata(): void {
    state.markDirty('metadata');
  }
  function markExtraction(): void {
    state.markDirty('extraction');
  }

  async function save(token = 'tok') {
    return runEditSave({
      token,
      api: {
        applyEdit:
          options.applyEdit ??
          (async (_t, _id, payload) => {
            void payload;
            return ticket ?? TICKET;
          }),
        getExtraction: options.getExtraction ?? (async () => extraction)
      },
      state,
      currentTicket: () => ticket,
      currentExtraction: () => extraction,
      onCommitted: (saved, refreshed) => {
        ticket = saved;
        if (refreshed) extraction = refreshed;
        onCommitted(saved, refreshed);
      },
      onSaveFailed,
      onUnstable
    });
  }

  return {
    state,
    save,
    onCommitted,
    onSaveFailed,
    onUnstable,
    edit,
    markMetadata,
    markExtraction,
    currentTicket: () => ticket,
    currentExtraction: () => extraction,
    setTicket: (t: CreatedTicket | null) => {
      ticket = t;
    }
  };
}

describe('createSaveState', () => {
  it('starts clean and never reports dirty until an edit lands', () => {
    const state = createSaveState();

    expect(state.isDirty()).toBe(false);
    expect(state.dirtySections().size).toBe(0);

    state.markDirty('metadata');

    expect(state.isDirty()).toBe(true);
    expect([...state.dirtySections()]).toEqual(['metadata']);
  });

  it('tracks the two sections independently', () => {
    const state = createSaveState();
    state.markDirty('metadata');
    state.markDirty('extraction');

    expect([...state.dirtySections()].sort()).toEqual(['extraction', 'metadata']);

    state.clear();

    expect(state.isDirty()).toBe(false);
  });

  it('bumps the generation on every edit and never on clear', () => {
    // The counter is what lets a save tell "nothing was typed
    // while I was in flight" from "something was". Clearing must
    // not rewind it, or a stale comparison could look stable.
    const state = createSaveState();
    const start = state.generation();

    state.markDirty('metadata');
    state.markDirty('extraction');
    state.clear();

    expect(state.generation()).toBe(start + 2);
    expect(state.generation()).toBeGreaterThan(start);
  });
});

describe('buildEditPayload', () => {
  it('omits the extraction entirely when only the metadata was edited', () => {
    // The concurrent-write guard: a metadata-only save must not
    // re-write an extraction row a concurrent AI run may have
    // refreshed since this screen loaded it.
    const payload = buildEditPayload(TICKET, EXTRACTION, new Set(['metadata']));

    expect(payload.title).toBe('Weekly shop');
    expect(payload.description).toBe('edited by hand');
    expect(payload.extraction).toBeNull();
  });

  it('sends null when the extraction is dirty but there is nothing to send', () => {
    // A ticket the AI has not read yet: null means "nothing to
    // write", never "clear it".
    const payload = buildEditPayload(TICKET, null, new Set(['extraction']));

    expect(payload.title).toBeUndefined();
    expect(payload.description).toBeUndefined();
    expect(payload.extraction).toBeNull();
  });

  it('omits metadata when only the extraction was edited', () => {
    const payload = buildEditPayload(TICKET, EXTRACTION, new Set(['extraction']));

    expect(payload.title).toBeUndefined();
    expect(payload.description).toBeUndefined();
    expect(payload.extraction?.merchant).toBe('Mercadona');
  });

  it('derives lineTotal as quantity × pricePerUnit', () => {
    // Never read back from the stored value: a stale lineTotal
    // must not reach the server.
    const stale: TicketExtraction = {
      ...EXTRACTION,
      products: [{ name: 'Bread', quantity: 3, unit: 'unit', pricePerUnit: 2, lineTotal: 999 }]
    };

    const payload = buildEditPayload(TICKET, stale, new Set(['extraction']));

    expect(payload.extraction?.products[0].lineTotal).toBe(6);
  });

  it('coerces a null quantity or price to zero-safe numbers', () => {
    const partial: TicketExtraction = {
      ...EXTRACTION,
      products: [
        // A half-typed row must serialise, not produce NaN.
        {
          name: 'Milk',
          quantity: null as unknown as number,
          unit: 'kg',
          pricePerUnit: null as unknown as number,
          lineTotal: 0
        }
      ]
    };

    const payload = buildEditPayload(TICKET, partial, new Set(['extraction']));

    expect(payload.extraction?.products[0]).toEqual({
      name: 'Milk',
      quantity: 1,
      unit: 'kg',
      pricePerUnit: 0,
      lineTotal: 0
    });
  });
});

describe('runEditSave', () => {
  it('does nothing when there is nothing dirty', async () => {
    const h = harness({});
    const applyEdit = vi.fn(async () => TICKET);

    const saved = await runEditSave({
      token: 'tok',
      api: { applyEdit, getExtraction: async () => EXTRACTION },
      state: h.state,
      currentTicket: h.currentTicket,
      currentExtraction: h.currentExtraction,
      onCommitted: h.onCommitted,
      onSaveFailed: h.onSaveFailed,
      onUnstable: h.onUnstable
    });

    expect(saved).toBe(true);
    expect(applyEdit).not.toHaveBeenCalled();
  });

  it('clears the dirty flag only once the server copy is adopted', async () => {
    const h = harness({});
    h.markMetadata();

    const saved = await h.save();

    expect(saved).toBe(true);
    expect(h.onCommitted).toHaveBeenCalledTimes(1);
    expect(h.state.isDirty()).toBe(false);
  });

  it('keeps edits made while the request was in flight and saves them next', async () => {
    // The regression the review flagged: the old code adopted the
    // server's ticket and cleared `dirty` unconditionally, so a
    // keystroke that landed during the round-trip was overwritten
    // and the screen reported itself clean.
    const firstPut = deferred<CreatedTicket>();
    const secondPut = deferred<CreatedTicket>();
    let putCount = 0;
    const applyEdit = vi.fn((_t: string, _id: number, _p: EditPayload) => {
      putCount += 1;
      return putCount === 1 ? firstPut.promise : secondPut.promise;
    });

    const h = harness({
      applyEdit,
      getExtraction: async () => EXTRACTION
    });
    h.markMetadata();

    const saving = h.save();

    // The user types while the first PUT is still open.
    h.edit(() => undefined);
    h.markMetadata();

    firstPut.resolve({ ...TICKET, title: 'Weekly shop' });
    await Promise.resolve();

    // The save must re-issue with the newer values rather than
    // declaring victory on the older snapshot.
    expect(applyEdit).toHaveBeenCalledTimes(2);
    secondPut.resolve({ ...TICKET, title: 'Latest title' });

    await expect(saving).resolves.toBe(true);
    expect(h.onCommitted).toHaveBeenCalledTimes(1);
    expect(h.state.isDirty()).toBe(false);
  });

  it('gives up (and reports it) when the user out-runs the retries', async () => {
    // Bounded on purpose: a fast typist on a slow connection must
    // degrade into "still editing", never into a request loop.
    // Each attempt is a deferred so the test decides when it
    // lands, and slips a fresh edit in before resolving it.
    const puts: Array<ReturnType<typeof deferred<CreatedTicket>>> = [];
    const applyEdit = vi.fn(() => {
      const d = deferred<CreatedTicket>();
      puts.push(d);
      return d.promise;
    });
    const h = harness({
      applyEdit,
      getExtraction: async () => EXTRACTION
    });
    h.markMetadata();

    const saving = h.save();

    // Exhaust the retry budget: three attempts, each one
    // invalidated by an edit that arrives before it resolves.
    for (let attempt = 0; attempt < 3; attempt += 1) {
      await vi.waitFor(() => expect(puts.length).toBe(attempt + 1));
      h.markMetadata();
      puts[attempt].resolve(TICKET);
    }

    await expect(saving).resolves.toBe(false);
    expect(applyEdit).toHaveBeenCalledTimes(3);
    expect(h.onUnstable).toHaveBeenCalled();
    expect(h.onCommitted).not.toHaveBeenCalled();
    expect(h.state.isDirty()).toBe(true);
  });

  it('reports failure and keeps the edits when the PUT rejects', async () => {
    const h = harness({
      applyEdit: async () => {
        throw new TicketApiError('boom', 500);
      }
    });
    h.markMetadata();

    await expect(h.save()).resolves.toBe(false);

    expect(h.onSaveFailed).toHaveBeenCalledTimes(1);
    expect(h.state.isDirty()).toBe(true);
    expect(h.onCommitted).not.toHaveBeenCalled();
  });

  it('counts the save as committed when only the audit re-read fails', async () => {
    // The PUT is the commit boundary. A failing follow-up GET must
    // not tell the user their edit was lost — it is already on the
    // server, only the "extracted by X on Y" stamp is stale.
    const h = harness({
      getExtraction: async () => {
        throw new TicketApiError('refresh down', 503);
      }
    });
    h.markExtraction();

    await expect(h.save()).resolves.toBe(true);

    expect(h.onSaveFailed).not.toHaveBeenCalled();
    expect(h.onCommitted).toHaveBeenCalledWith(expect.anything(), null);
    expect(h.state.isDirty()).toBe(false);
  });

  it('re-reads the extraction only when the extraction half was written', async () => {
    // A metadata-only save must not spend a request on a GET whose
    // result it would discard.
    const getExtraction = vi.fn(async () => EXTRACTION);
    const h = harness({ getExtraction });
    h.markMetadata();

    await h.save();

    expect(getExtraction).not.toHaveBeenCalled();
  });

  it('returns true when there is no ticket to edit', async () => {
    const h = harness({ ticket: null });
    h.markMetadata();

    await expect(h.save()).resolves.toBe(true);
    expect(h.onCommitted).not.toHaveBeenCalled();
  });
});

describe('save flushed before a status change closes the view', () => {
  it('persists the edits before the status PATCH and only then closes', async () => {
    // "Mark as done" must not discard what the user just typed.
    const calls: string[] = [];
    const save = deferred<boolean>();
    const state = createSaveState();
    state.markDirty('metadata');

    const h = harness({
      applyEdit: async () => {
        calls.push('save');
        return TICKET;
      },
      getExtraction: async () => EXTRACTION,
      state
    });

    const statusCall = deferred<void>();
    const progressed = flushThenAct(
      async () => {
        calls.push('flush');
        return save.promise;
      },
      async () => {
        calls.push('status');
        await statusCall.promise;
        calls.push('close');
      }
    );

    // The status action must not have started while the save is open.
    expect(calls).toEqual(['flush']);
    save.resolve(await h.save());
    statusCall.resolve();
    await progressed;
    void h;

    expect(calls).toEqual(['flush', 'save', 'status', 'close']);
    expect(state.isDirty()).toBe(false);
  });

  it('keeps the view open when the flush fails', async () => {
    // The half-applied state this ordering exists to prevent: a
    // DONE ticket still carrying pre-edit data.
    const act = vi.fn(async () => undefined);
    const h = harness({
      applyEdit: async () => {
        throw new TicketApiError('boom', 500);
      }
    });
    h.markMetadata();

    const progressed = await flushThenAct(() => h.save(), act);

    expect(progressed).toBe(false);
    expect(act).not.toHaveBeenCalled();
    expect(h.state.isDirty()).toBe(true);
  });

  it('leaves the edits saved and clean when the status change itself fails', async () => {
    // The save did its job; only the transition failed. The view
    // stays open with nothing to re-send.
    const h = harness({});
    h.markMetadata();
    expect(await h.save()).toBe(true);

    await expect(
      flushThenAct(
        async () => true,
        async () => {
          throw new TicketApiError('status 409', 409);
        }
      )
    ).rejects.toThrow('status 409');

    expect(h.state.isDirty()).toBe(false);
    expect(h.onCommitted).toHaveBeenCalledTimes(1);
  });

  it('joins an in-flight save instead of duplicating it', async () => {
    // The Save button and "Mark as done" are both reachable while a
    // save runs, and two concurrent writes could land out of
    // order. The component shares one promise between them; this
    // pins that the shared promise yields a single write.
    const put = deferred<CreatedTicket>();
    const applyEdit = vi.fn(() => put.promise);
    const state = createSaveState();
    state.markDirty('metadata');
    const h = harness({ applyEdit, state });

    // Mirrors TicketDetailApp.saveChanges: one slot, first caller
    // fills it, everyone else awaits it.
    let saveInFlight: Promise<boolean> | null = null;
    const save = async (): Promise<boolean> => {
      if (saveInFlight) return saveInFlight;
      saveInFlight = h.save();
      try {
        return await saveInFlight;
      } finally {
        saveInFlight = null;
      }
    };

    const fromSaveButton = save();
    const fromStatusAction = save();

    put.resolve(TICKET);

    await expect(fromSaveButton).resolves.toBe(true);
    await expect(fromStatusAction).resolves.toBe(true);
    // One shared promise → one write, not two.
    expect(applyEdit).toHaveBeenCalledTimes(1);
    expect(h.onCommitted).toHaveBeenCalledTimes(1);
  });
});
