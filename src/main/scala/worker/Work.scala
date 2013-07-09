package worker

case class Work(workId: String, job: Any)

case class WorkResult(workId: String, result: Any)