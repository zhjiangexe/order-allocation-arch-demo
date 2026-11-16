export const documents = [
  { id: 'index', file: '00_PROJECT_OVERVIEW.md', title: '專案導覽' },
  { id: 'business', file: '01_BUSINESS_FLOW_AND_SCOPE.md', title: '業務流程與範疇', number: '01' },
  { id: 'bpmn', file: '02_BPMN_PROCESS_MODELS.md', title: 'BPMN 流程圖集', number: '02' },
  { id: 'data-model', file: '03_DATA_MODEL_AND_STORAGE.md', title: '資料模型與儲存', number: '03' },
  { id: 'data-dictionary', file: '04_DATA_DICTIONARY.md', title: '資料字典', number: '04' },
  { id: 'integration', file: '06_INTEGRATION_AND_SEQUENCE.md', title: '整合與交易時序', number: '06' },
  { id: 'bdd', file: '07_BDD_FEATURES.md', title: 'BDD 規格案例', number: '07' },
  { id: 'verification', file: '08_UAT_AND_VERIFICATION.md', title: 'UAT 與驗證', number: '08' },
  { id: 'runbook', file: '09_TROUBLESHOOTING_RUNBOOK.md', title: '故障排除手冊', number: '09' },
  { id: 'bpmn-guide', file: 'bpmn/README.md', title: 'BPMN 模型說明', hidden: true, parent: 'bpmn' },
  { id: 'process-spec', file: 'bpmn/process-spec.md', title: 'BPMN 流程規格', hidden: true, parent: 'bpmn' },
];
export const apiMeta = { id: 'api', title: 'OPENAPI', number: '05' };
