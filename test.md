ArchivingApplication (scheduler)
  ├─ monitor3() → tarUseCase.receivedFiles()
  │     └─ TarDomainAcquisitionUseCase.receivedFiles()
  │           → storageService.listFilePaths(inDir)
  │           → extractAndMove() [GzipCompressor → TarArchiveInputStream]
  │
  └─ monitor4() → tarUseCase.sendFiles()
        └─ TarDomainAcquisitionUseCase.sendFiles()
              → list XMLs from ongoingDir
              → TarParsingProcessor (parse XML + charge le PDF associé)
              → [chaîne de filtres]
              → SendProcessor → EaaSStAdapter.send()
                    ├─ createOneDigitalObject() [POST multipart]
                    ├─ createContext()          [POST JSON]
                    ├─ createDDToContextLink()  [PUT]
                    └─ updateContext()          [PUT avec documentId]


