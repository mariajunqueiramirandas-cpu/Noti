import { z } from "zod";

export const ASSAAD_ORIGIN = "https://app.plataformaassaad.com.br";
const id = z.string().min(1).max(100).regex(/^[a-zA-Z0-9_-]+$/);
const image = z.object({ alt: z.string().max(2000), url: z.string().url().max(4096).nullable() }).strict();
const images = z.array(image).max(20);
export const question = z.object({
  id, source: z.string().max(2000), tags: z.array(z.string().max(200)).max(30),
  difficulty: z.string().max(100).nullable(), prompt: z.string().max(50000), images,
  alternatives: z.array(z.object({ id, label: z.string().min(1).max(10), text: z.string().max(20000), images }).strict()).min(2).max(12)
}).strict().refine(q => new Set(q.alternatives.map(a => a.id)).size === q.alternatives.length &&
  q.images.length + q.alternatives.reduce((total, a) => total + a.images.length, 0) <= 16);
export const batch = z.object({
  batchId: z.string().uuid(), source: z.object({
    url: z.string().url().max(4096).refine(url => {
      const parsed = new URL(url);
      return parsed.origin === ASSAAD_ORIGIN && /^\/banco-de-questoes\/explorar\/?$/.test(parsed.pathname);
    }), page: z.string().max(30).nullable()
  }).strict(), capturedAt: z.string().datetime(), questions: z.array(question).min(1).max(100)
}).strict().refine(b => new Set(b.questions.map(q => q.id)).size === b.questions.length);
export const presence = z.object({ active: z.boolean(), path: z.enum(["/", "/banco-de-questoes/explorar", "/other"]).nullable() }).strict();
export const answer = z.object({ commandId: z.string().uuid(), batchId: z.string().uuid(), questionId: id, alternativeId: id }).strict();
export const answerResult = z.object({
  commandId: z.string().uuid(), status: z.enum(["confirmed", "failed", "unknown"]),
  correct: z.boolean().nullable(), correctAlternativeId: id.nullable(), feedback: z.string().max(10000)
}).strict();
export type Batch = z.infer<typeof batch>;
export type Answer = z.infer<typeof answer>;
export type AnswerResult = z.infer<typeof answerResult>;
export type Presence = z.infer<typeof presence>;
export type Role = "app" | "browser";
export const clientMessage = z.discriminatedUnion("type", [
  z.object({ type: z.literal("ping") }).strict(),
  z.object({ type: z.literal("presence"), payload: presence }).strict(),
  z.object({ type: z.literal("questions"), payload: batch }).strict(),
  z.object({ type: z.literal("answer"), payload: answer }).strict(),
  z.object({ type: z.literal("answer-result"), payload: answerResult }).strict()
]);
