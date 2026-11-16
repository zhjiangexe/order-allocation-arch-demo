import mermaid from 'mermaid';
mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'base', fontFamily: 'Arial, PingFang TC, sans-serif', htmlLabels: false, themeVariables: { primaryColor: '#ffffff', primaryTextColor: '#222222', primaryBorderColor: '#555555', lineColor: '#555555', secondaryColor: '#f5f5f5', tertiaryColor: '#ffffff', fontSize: '17px', noteBkgColor: '#f5f5f5', noteTextColor: '#222222' }, flowchart: { htmlLabels: false, curve: 'linear', useMaxWidth: true }, sequence: { useMaxWidth: true }, er: { useMaxWidth: true } });
let serial = Promise.resolve();
let number = 0;
export function renderDiagram(figure) {
  serial = serial.then(async () => {
    const source = figure.querySelector('.mermaid-source');
    if (!source) return;
    try {
      const { svg } = await mermaid.render(`diagram-${++number}`, source.textContent);
      figure.querySelector('.diagram-preview').innerHTML = svg;
      const diagram = figure.querySelector('svg');
      diagram.setAttribute('role', 'img');
      diagram.setAttribute('aria-label', '流程與架構圖，可使用上方按鈕放大');
      figure.querySelector('[data-diagram-open]').disabled = false;
      figure.dataset.rendered = 'true';
    } catch (error) {
      figure.dataset.rendered = 'error';
      const notice = document.createElement('p');
      notice.className = 'empty-state';
      notice.textContent = '圖表暫時無法顯示，請重新整理頁面或下載 Markdown 文件。';
      figure.querySelector('.diagram-preview').replaceChildren(notice);
      console.error('Diagram rendering failed', error);
    }
  });
  return serial;
}
