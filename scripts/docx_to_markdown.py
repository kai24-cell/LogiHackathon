"""Extract every paragraph and table in document order using only the standard library."""
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

NS = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

def text(node):
    pieces = []
    for element in node.iter():
        if element.tag == NS + 't':
            pieces.append(element.text or '')
        elif element.tag == NS + 'tab':
            pieces.append('\t')
        elif element.tag in (NS + 'br', NS + 'cr'):
            pieces.append('\n')
    return ''.join(pieces)

def render(node):
    if node.tag == NS + 'p':
        value = text(node)
        style = node.find('./' + NS + 'pPr/' + NS + 'pStyle')
        name = style.get(NS + 'val', '') if style is not None else ''
        if name.lower().startswith('heading') and name[-1:].isdigit():
            value = '#' * int(name[-1]) + ' ' + value
        return value + '\n\n'
    if node.tag == NS + 'tbl':
        rows = []
        for row in node.findall(NS + 'tr'):
            cells = []
            for cell in row.findall(NS + 'tc'):
                contents = ''.join(
                    render(child) for child in cell
                    if child.tag in (NS + 'p', NS + 'tbl')
                )
                cells.append(contents.strip().replace('|', '\\|').replace('\n', '<br>'))
            rows.append(cells)
        width = max(map(len, rows), default=0)
        lines = [
            '| ' + ' | '.join(row + [''] * (width - len(row))) + ' |'
            for row in rows
        ]
        if lines:
            lines.insert(1, '| ' + ' | '.join(['---'] * width) + ' |')
        return '\n'.join(lines) + '\n\n'
    return ''.join(render(child) for child in node)

for source in sorted(Path('docs').glob('*.docx')):
    with ZipFile(source) as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
        output = render(root.find(NS + 'body'))
        for name in archive.namelist():
            extra_parts = ('word/footnotes', 'word/endnotes', 'word/header', 'word/footer')
            if name.startswith(extra_parts) and name.endswith('.xml'):
                output += '\n## ' + name + '\n\n' + render(ET.fromstring(archive.read(name)))
    source.with_suffix('.md').write_text(output, encoding='utf-8')
    print(
        source.name,
        'paragraphs:', len(root.findall('.//' + NS + 'p')),
        'tables:', len(root.findall('.//' + NS + 'tbl')),
    )
