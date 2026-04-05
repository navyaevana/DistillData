import { useState, useEffect } from 'react'
import axios from 'axios'
import './App.css'

interface DatasetMetadata {
  id: number;
  fileName: string;
  fileType: string;
  originalRowCount: number;
  cleanedRowCount: number;
  uploadTime: string;
  status: string;
  analysisResults: string;
  processedFilePath: string;
  originalData: string;
  cleanedData: string;
}

interface CleaningOptions {
  removeNulls: boolean;
  removeDuplicates: boolean;
  removeEmptyRows: boolean;
  trimWhitespace: boolean;
}

interface CleaningResult {
  datasetId: number;
  originalRowCount: number;
  cleanedRowCount: number;
  duplicatesRemoved: number;
  nullRowsRemoved: number;
  originalData: string;
  cleanedData: string;
  analysisResults: string;
}

function App() {
  const [datasets, setDatasets] = useState<DatasetMetadata[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [viewingData, setViewingData] = useState<DatasetMetadata | null>(null);
  const [activeTab, setActiveTab] = useState<'original' | 'cleaned' | 'analysis' | 'cleaning'>('original');
  const [cleaningOptions, setCleaningOptions] = useState<CleaningOptions>({
    removeNulls: true,
    removeDuplicates: true,
    removeEmptyRows: true,
    trimWhitespace: true
  });
  const [cleaningResult, setCleaningResult] = useState<CleaningResult | null>(null);
  const [isCleaning, setIsCleaning] = useState(false);

  useEffect(() => {
    fetchDatasets();
  }, []);

  const fetchDatasets = async () => {
    try {
      const response = await axios.get('http://localhost:8081/api/data/datasets');
      setDatasets(response.data);
    } catch (error) {
      console.error('Error fetching datasets:', error);
    }
  };

  const handleFileUpload = async () => {
    if (!selectedFile) return;

    setUploading(true);
    const formData = new FormData();
    formData.append('file', selectedFile);

    try {
      const response = await axios.post('http://localhost:8081/api/data/upload', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      });
      setDatasets(prev => [response.data, ...prev]);
      setSelectedFile(null);
      alert('File uploaded and processed successfully!');
    } catch (error) {
      console.error('Error uploading file:', error);
      alert('Error uploading file');
    } finally {
      setUploading(false);
    }
  };

  const handleApplyCleaning = async () => {
    if (!viewingData) return;

    setIsCleaning(true);
    try {
      const response = await axios.post(`http://localhost:8081/api/data/clean/${viewingData.id}`, cleaningOptions);
      setCleaningResult(response.data);
      setActiveTab('cleaning');
      alert(`✓ Cleaning complete!\nDuplicates removed: ${response.data.duplicatesRemoved}\nNull rows removed: ${response.data.nullRowsRemoved}`);
      
      // Refresh dataset list
      fetchDatasets();
    } catch (error) {
      console.error('Error applying cleaning:', error);
      alert('Error applying cleaning options');
    } finally {
      setIsCleaning(false);
    }
  };

  const handleDownload = async (id: number) => {
    try {
      const response = await axios.get(`http://localhost:8081/api/data/download/${id}`, {
        responseType: 'blob',
      });

      // Get filename from response headers
      const contentDisposition = response.headers['content-disposition'];
      let filename = `processed_${id}.csv`; // fallback

      if (contentDisposition) {
        const filenameMatch = contentDisposition.match(/filename="(.+)"/);
        if (filenameMatch) {
          filename = filenameMatch[1];
        }
      }

      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', filename);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
    } catch (error) {
      console.error('Error downloading file:', error);
      alert('Failed to download file. Please try again.');
    }
  };

  const viewData = async (dataset: DatasetMetadata) => {
    try {
      const response = await axios.get(`http://localhost:8081/api/data/datasets/${dataset.id}`);
      setViewingData(response.data);
      setCleaningResult(null);
      setActiveTab('original');
    } catch (error) {
      console.error('Error fetching dataset details:', error);
      setViewingData(dataset);
    }
  };

  const closeView = () => {
    setViewingData(null);
    setCleaningResult(null);
  };

  const renderDataTable = (dataJson: string) => {
    try {
      const data = JSON.parse(dataJson);
      if (!Array.isArray(data) || data.length === 0) return <p>No data available</p>;

      return (
        <div className="data-table-container">
          <table className="data-table">
            <tbody>
              {data.slice(0, 100).map((row, rowIdx) => (
                <tr key={rowIdx}>
                  {Array.isArray(row) ? row.map((cell, cellIdx) => (
                    <td key={cellIdx}>{String(cell)}</td>
                  )) : Object.values(row).map((cell, cellIdx) => (
                    <td key={cellIdx}>{String(cell)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {data.length > 100 && <p className="info-text">Showing first 100 rows of {data.length} total rows</p>}
        </div>
      );
    } catch (error) {
      return <p>Error parsing data</p>;
    }
  };

  const renderAnalysis = (analysisJson: string) => {
    try {
      const analysis = JSON.parse(analysisJson);
      return (
        <div className="analysis-container">
          <p><strong>Total Rows:</strong> {analysis.totalRows}</p>
          <p><strong>Total Columns:</strong> {analysis.totalColumns}</p>
          <div className="columns-analysis">
            <h4>Column Analysis:</h4>
            {analysis.columns && analysis.columns.map((col: any, idx: number) => (
              <div key={idx} className="column-info">
                <h5>{col.name}</h5>
                <p>Non-Null: {col.nonNullCount} | Null: {col.nullCount}</p>
                {col.isNumeric && (
                  <p>Min: {col.min?.toFixed(2)} | Max: {col.max?.toFixed(2)} | Avg: {col.average?.toFixed(2)}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      );
    } catch (error) {
      return <p>Error parsing analysis data</p>;
    }
  };

  return (
    <div className="app">
      <header>
        <h1>🗂️ Smart Data Processing Application</h1>
        <p>Upload, Clean, Analyze & Download your data with custom cleaning options</p>
      </header>

      <main>
        <section className="upload-section">
          <h2>📤 Upload Dataset</h2>
          <div className="upload-controls">
            <input
              type="file"
              accept=".csv,.xlsx,.xls"
              onChange={(e) => setSelectedFile(e.target.files?.[0] || null)}
            />
            <button
              onClick={handleFileUpload}
              disabled={!selectedFile || uploading}
              className="btn-primary"
            >
              {uploading ? '⏳ Processing...' : '✓ Upload & Process'}
            </button>
          </div>
        </section>

        <section className="datasets-section">
          <h2>📊 Processed Datasets</h2>
          <div className="datasets-list">
            {datasets.length === 0 ? (
              <p className="empty-message">No datasets yet. Upload a file to get started!</p>
            ) : (
              datasets.map(dataset => (
                <div key={dataset.id} className="dataset-card">
                  <h3>📄 {dataset.fileName}</h3>
                  <div className="card-info">
                    <p><strong>Type:</strong> {dataset.fileType}</p>
                    <p><strong>Original Rows:</strong> {dataset.originalRowCount}</p>
                    <p><strong>Cleaned Rows:</strong> {dataset.cleanedRowCount}</p>
                    <p><strong>Rows Removed:</strong> {dataset.originalRowCount - dataset.cleanedRowCount}</p>
                    <p><strong>Status:</strong> <span className="status-badge">{dataset.status}</span></p>
                    <p><small>{new Date(dataset.uploadTime).toLocaleString()}</small></p>
                  </div>
                  <div className="actions">
                    <button onClick={() => viewData(dataset)} className="btn-secondary">View & Clean</button>
                    <button onClick={() => handleDownload(dataset.id)} className="btn-success">⬇️ Download</button>
                  </div>
                </div>
              ))
            )}
          </div>
        </section>

        {viewingData && (
          <div className="modal">
            <div className="modal-content modal-large">
              <div className="modal-header">
                <h2>🔧 Data Processing: {viewingData.fileName}</h2>
                <button className="close-x" onClick={closeView}>✕</button>
              </div>

              <div className="tabs">
                <button 
                  className={`tab-button ${activeTab === 'original' ? 'active' : ''}`}
                  onClick={() => setActiveTab('original')}
                >
                  📋 Original Data ({viewingData.originalRowCount} rows)
                </button>
                <button 
                  className={`tab-button ${activeTab === 'cleaned' ? 'active' : ''}`}
                  onClick={() => setActiveTab('cleaned')}
                >
                  ✨ Cleaned Data ({viewingData.cleanedRowCount} rows)
                </button>
                <button 
                  className={`tab-button ${activeTab === 'analysis' ? 'active' : ''}`}
                  onClick={() => setActiveTab('analysis')}
                >
                  📈 Analysis Results
                </button>
                <button 
                  className={`tab-button ${activeTab === 'cleaning' ? 'active' : ''}`}
                  onClick={() => setActiveTab('cleaning')}
                >
                  🧹 Cleaning Options
                </button>
              </div>

              <div className="tab-content">
                {activeTab === 'original' && renderDataTable(viewingData.originalData)}
                {activeTab === 'cleaned' && renderDataTable(viewingData.cleanedData)}
                {activeTab === 'analysis' && renderAnalysis(viewingData.analysisResults)}
                {activeTab === 'cleaning' && (
                  <div className="cleaning-options-panel">
                    <h3>🧹 Choose Your Cleaning Options</h3>
                    <div className="options-group">
                      <label className="checkbox-label">
                        <input 
                          type="checkbox" 
                          checked={cleaningOptions.removeNulls}
                          onChange={(e) => setCleaningOptions({...cleaningOptions, removeNulls: e.target.checked})}
                        />
                        <span>Remove Null/Empty Rows</span>
                      </label>
                      <p className="option-description">Remove rows that are completely empty or contain only null values</p>
                    </div>

                    <div className="options-group">
                      <label className="checkbox-label">
                        <input 
                          type="checkbox" 
                          checked={cleaningOptions.removeDuplicates}
                          onChange={(e) => setCleaningOptions({...cleaningOptions, removeDuplicates: e.target.checked})}
                        />
                        <span>Remove Duplicate Rows</span>
                      </label>
                      <p className="option-description">Remove rows that have identical data in all columns</p>
                    </div>

                    <div className="options-group">
                      <label className="checkbox-label">
                        <input 
                          type="checkbox" 
                          checked={cleaningOptions.trimWhitespace}
                          onChange={(e) => setCleaningOptions({...cleaningOptions, trimWhitespace: e.target.checked})}
                        />
                        <span>Trim Whitespace</span>
                      </label>
                      <p className="option-description">Remove leading and trailing spaces from all cells</p>
                    </div>

                    <div className="options-group">
                      <label className="checkbox-label">
                        <input 
                          type="checkbox" 
                          checked={cleaningOptions.removeEmptyRows}
                          onChange={(e) => setCleaningOptions({...cleaningOptions, removeEmptyRows: e.target.checked})}
                        />
                        <span>Remove Empty Rows</span>
                      </label>
                      <p className="option-description">Remove rows with all empty cells</p>
                    </div>

                    <button 
                      onClick={handleApplyCleaning}
                      disabled={isCleaning}
                      className="btn-primary btn-large"
                    >
                      {isCleaning ? '⏳ Cleaning...' : '✓ Apply Cleaning'}
                    </button>
                  </div>
                )}

                {cleaningResult && activeTab === 'cleaning' && (
                  <div className="cleaning-results">
                    <h3>✓ Cleaning Complete!</h3>
                    <div className="results-grid">
                      <div className="result-item">
                        <p className="result-label">Original Rows</p>
                        <p className="result-value">{cleaningResult.originalRowCount}</p>
                      </div>
                      <div className="result-item">
                        <p className="result-label">Cleaned Rows</p>
                        <p className="result-value success">{cleaningResult.cleanedRowCount}</p>
                      </div>
                      <div className="result-item">
                        <p className="result-label">Duplicates Removed</p>
                        <p className="result-value warning">{cleaningResult.duplicatesRemoved}</p>
                      </div>
                      <div className="result-item">
                        <p className="result-label">Null Rows Removed</p>
                        <p className="result-value warning">{cleaningResult.nullRowsRemoved}</p>
                      </div>
                    </div>
                  </div>
                )}
              </div>

              <div className="modal-footer">
                <button onClick={closeView} className="btn-secondary">Close</button>
                <button onClick={() => handleDownload(viewingData.id)} className="btn-success">⬇️ Download Processed File</button>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

export default App;
