var g = require('ngraph.graph')()
var renderGraph = require('ngraph.pixel')

fetch('./nomads.json')
  .then(r => r.json())
  .then(render)

function render(data) {
  const graph = {
    nodes: [],
    edges: []
  }

  for (nomad of data) {
    graph.nodes.push({
      id: nomad.username,
      label: nomad.username + ' (' + nomad.followers.length + ')'
    })
  }
  for (nomad of data) {
    for (follower of nomad.followers) {
      graph.edges.push({
        id: nomad.username + '_' + follower,
        source: nomad.username,
        target: follower
      })

      // shouldn't happen if everything is there..
      if (graph.nodes.map(n => n.id).indexOf(follower) === -1) {
        graph.nodes.push({
          id: follower,
          label: follower
        })
      }
    }
  }

  graph.nodes.forEach(node => g.addNode(node.id, node))
  graph.edges.forEach(edge => g.addLink(edge.source, edge.target, 'hidden'))

  const physics = {
    springLength: 30,
    springCoeff: 0.0008,
    gravity: -1.2,
    theta: 0.8,
    dragCoeff: 0.02,
    timeStep: 20
  }

  const renderer = renderGraph(g, {
    node: createNodeUI,
    link: createLinkUI,
    container: document.querySelector('.container'),
    clearAlpha: 0.0
    //physics
  })

  function createNodeUI(node) {
    if (!node.links) {
      return {
        color: 0xff00ff,
        size: 1
      }
    }
    return {
      color: 0xff00ff,
      size: Math.floor(node.links.length / 10) + 4
    }
  }

  function createLinkUI(link) {
    if (link.data === 'hidden') return
    return {
      fromColor: 0xff00ff,
      toColor: 0x00ffff
    }
  }
}
