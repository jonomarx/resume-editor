import type { components } from './schema'

export type User = components['schemas']['User']
export type Credentials = components['schemas']['Credentials']
export type SignUpRequest = components['schemas']['SignUpRequest']
export type ApiErrorBody = components['schemas']['Error'] & { currentVersion?: number; conflictingSlug?: string; existingSourceId?: number }
export type ResumeDocument = components['schemas']['ResumeDocument']
export type ResumeVersionSummary = components['schemas']['ResumeVersionSummary']
export type ResumeTree = components['schemas']['ResumeTree']
export type Tag = components['schemas']['Tag']
export type TagCategory = components['schemas']['TagCategory']
export type Source = components['schemas']['Source']
export type SourceHealth = components['schemas']['SourceHealth']
export type CrawlRun = components['schemas']['CrawlRun']
export type IngestKind = components['schemas']['IngestRequest']['kind']
export type IngestAccepted = components['schemas']['IngestAccepted']
export type IngestEvent = components['schemas']['IngestEvent']
export type IngestToken = components['schemas']['IngestToken']
export type CreatedIngestToken = components['schemas']['CreatedIngestToken']
export type JobSummary = components['schemas']['JobSummary']
export type JobPage = components['schemas']['JobPage']
export type JobUserStatus = components['schemas']['JobUserStatus']
export type RenderResult = components['schemas']['RenderResult']

/** Error thrown by any API client call; mirrors the contract's Error schema. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  /** Present on 409 version_conflict: the version now stored. */
  readonly currentVersion?: number
  /** Present on 409 alias_conflict: the tag that already holds the name. */
  readonly conflictingSlug?: string
  /** Present on 409 source_exists: the source the user already has. */
  readonly existingSourceId?: number
  /** Per-field problems on 400 validation_failed. */
  readonly details?: ApiErrorBody['details']

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
    this.currentVersion = body.currentVersion
    this.conflictingSlug = body.conflictingSlug
    this.existingSourceId = body.existingSourceId
    this.details = body.details
  }
}

/** Auth operations (openapi operationIds signUp, logIn, logOut, getMe). */
export interface AuthApi {
  signUp(credentials: SignUpRequest & Credentials): Promise<User>
  logIn(credentials: Credentials): Promise<User>
  logOut(): Promise<void>
  /** Resolves to null when nobody is signed in (401). */
  getMe(): Promise<User | null>
}

/** Master resume operations (operationIds getMasterResume, saveMasterResume, listMasterVersions, restoreMasterVersion). */
export interface ResumeApi {
  getMaster(): Promise<ResumeDocument>
  /** 409 rejects with an ApiError carrying currentVersion. */
  saveMaster(baseVersion: number, tree: ResumeTree): Promise<ResumeDocument>
  listVersions(): Promise<ResumeVersionSummary[]>
  restoreVersion(version: number, baseVersion: number): Promise<ResumeDocument>
}

/** Render operation (operationId renderResume). Takes the tree, not LaTeX; the PDF itself is fetched via result.pdfUrl. */
export interface RenderApi {
  render(tree: ResumeTree): Promise<RenderResult>
}

/** Tag vocabulary (operationIds listTags, createTag). Aliases are not managed by the node tag editor. */
export interface TagsApi {
  listTags(): Promise<Tag[]>
  /** Get or create: resolves to the existing tag (200) or the new one (201). */
  createTag(name: string, category: TagCategory): Promise<Tag>
}

/** Career page sources (operationIds listSources, createSource, updateSource, deleteSource, getSourceHealth). */
export interface SourcesApi {
  list(): Promise<Source[]>
  create(url: string, name?: string): Promise<Source>
  update(id: number, changes: { name?: string; enabled?: boolean }): Promise<Source>
  remove(id: number): Promise<void>
  /** One entry per source, each carrying its Source. */
  health(): Promise<SourceHealth[]>
}

/** Ingest (operationIds ingestPosting, getIngestEvent, listIngestTokens, createIngestToken, revokeIngestToken). Session calls only; the bearer token is for the bookmarklet. */
export interface IngestApi {
  ingest(kind: IngestKind, payload: string, sourceHint?: string): Promise<IngestAccepted>
  getEvent(id: number): Promise<IngestEvent>
  listTokens(): Promise<IngestToken[]>
  /** The secret is in the result once and never again. */
  createToken(label: string): Promise<CreatedIngestToken>
  revokeToken(id: number): Promise<void>
}

/** Query of the job feed (operationId listJobs). Empty or undefined fields are left out of the request. */
export interface JobQuery {
  sort: 'fit' | 'newest'
  /** Tag slugs; a posting must carry every one. */
  tags: string[]
  location?: string
  status?: JobUserStatus
  maxAgeDays?: number
  q?: string
  limit: number
  offset: number
}

/** Job feed (operationIds listJobs, setJobStatus). */
export interface JobsApi {
  list(query: JobQuery): Promise<JobPage>
  /** Sets the user's own status for a posting; resolves to the posting. */
  setStatus(id: number, status: JobUserStatus): Promise<JobSummary>
}

export interface Api {
  jobs: JobsApi
  ingest: IngestApi
  sources: SourcesApi
  tags: TagsApi
  auth: AuthApi
  resume: ResumeApi
  render: RenderApi
}
