function Button(props) {
    const classes = "button is-large "+props.colour;
    return (
        <button className={classes} value={props.value} onClick={() => props.onClick()}>{props.text}</button>
    )
}

class Question extends React.Component {
    render() {
        const question = this.props.question.question;
        const answers = this.props.question.answers;
        const answerButtons = answers.map((answer, index) => {
            const colour = (this.props.answer === answer) ? "is-success" : "";
            return (
                <Button key={index} colour={colour} text={answer.text} onClick={() => this.props.onAnswer(answer)}/>
            );
        });
        return (
            <div className="question">
                <h2 className="heading">{question}</h2>
                {answerButtons}
            </div>
        )
    }
}

class Rsvp extends React.Component {
    map(o, f, ctx) {
        ctx = ctx || this;
        let result = {};
        Object.keys(o).forEach(function(k) {
            result[k] = f.call(ctx, o[k], k, o);
        });
        return result;
    }
    constructor() {
        super();
        this.state = {
            questions: null,
            startKey: null,
            answers: {}
        }
    }
    findAnswer(questions, questionKey, answerKey) {
        const question = questions[questionKey];
        return question.answers.find((answer) => { return answer.updateKey === answerKey });
    }
    componentDidMount() {
        // get questions
        fetch("/rsvp/questions", {
            credentials: 'include'
        }).then((response) => {

            return response.json().then((json) => {
                const answerMap = json.answers;
                const answerMapWithObjects = this.map(answerMap, (v, k) => {
                    return this.findAnswer(json.questions, k, v)
                });
                this.setState({questions: json.questions, startKey: json.startPage, answers: answerMapWithObjects});
            });
        });
        // get any responses
    }
    update(answers, complete) {
        return fetch("/rsvp/update?complete="+complete, {
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Csrf-Token': window.csrf
            },
            method: 'POST',
            body: JSON.stringify(answers)
        })
    }
    onAnswer(question, answer) {
        const answers = {};
        answers[question.updateKey] = answer.updateKey;
        this.update(answers, false).then((res) => {
            if (res.status == 200) {
                this.setState((previous) => {
                    const newAnswer = {};
                    newAnswer[question.updateKey] = answer;
                    return {answers: Object.assign({}, previous.answers, newAnswer)};
                });
            }
        });
    }
    questionAnswers(key) {
        const question = this.state.questions[key];
        if (question) {
            const answer = this.state.answers[question.updateKey];
            if (answer) {
                return [{question: question, answer: answer}].concat(this.questionAnswers(answer.nextQuestion));
            } else {
                return [{question: question}];
            }
        } else return [];
    }
    renderQuestion(question, answer) {
        return (
            <Question key={question.updateKey} question={question} answer={answer} onAnswer={(answer) => this.onAnswer(question, answer)}/>
        );
    }
    finishRsvp() {
        console.log("finished!");
        const answers = this.map(this.state.answers, (v) => {
            return v.updateKey
        });
        this.update(answers, true).then((res) => {
            if (res.status == 200) {
                window.location = '/rsvp/complete'
            }
        })
    }
    render() {
        if (this.state.questions) {
            const questionList = this.questionAnswers(this.state.startKey);
            const questionElements = questionList.map((question) => {
                return this.renderQuestion(question.question, question.answer);
            });
            const bottomQuestion = questionList[questionList.length-1];
            const terminusQuestion = bottomQuestion.question.nextQuestion === undefined;
            const finished = terminusQuestion && bottomQuestion.answer;
            return (
                <div>
                    {questionElements}
                    {finished &&
                        <div>
                            <h2 className="heading">You're finished!</h2>
                            <Button colour="is-primary" onClick={() => this.finishRsvp()} text="Send RSVP"/>
                        </div>
                    }
                </div>
            );
        } else {
            return (
                <p>Loading RSVP information...</p>
            );
        }
    }
}

ReactDOM.render(
    <Rsvp />,
    document.getElementById('container')
);